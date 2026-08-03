package com.lightphone.spotify.playback.download

import android.content.Context
import android.util.Log
import com.lightphone.spotify.App
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.data.toMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps chosen parts of the library on the phone without being asked each time.
 *
 * Two things can be kept: the newest of Liked Songs, and the Daily Mixes. Both are opt-in and both
 * have a ceiling — see [AutoPinPlan], which holds the decisions this class only executes.
 *
 * ### When it runs
 * On the **same daily alarm the podcast checker already owns**, rather than an alarm of its own.
 * Android clamps `setAndAllowWhileIdle` to roughly one firing every fifteen minutes per app, and two
 * alarms a day apart would compete for that budget for no gain: both checks want the same moment,
 * which is "overnight, before you leave". [PodcastAlarmReceiver] calls both.
 *
 * It also runs at app start, for the same reason podcasts do — you open the app on Wi-Fi before
 * going out, and that is the cheapest chance to top up.
 *
 * ### Why the mixes are compared rather than re-fetched
 * A Daily Mix is regenerated daily, so pinning one is only worth doing when its contents have
 * actually changed. Comparing membership against what is already downloaded, rather than the date or
 * a version, means a mix that came back the same costs one metadata call and no audio.
 */
object LibraryAutoDownload {
    private const val TAG = "LibraryAutoDownload"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Same guard the podcast checker uses: opening the app repeatedly should be free. */
    private const val MIN_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** The collection row Liked Songs is pinned under. Not a real Spotify uri — there is none for
     * "the tracks I have saved" as a playable collection — so it is namespaced to this app to keep it
     * from colliding with a playlist of the same id. */
    const val LIKED_COLLECTION_URI = "phono:auto:liked"

    fun checkNow(context: Context, force: Boolean = false) {
        val app = context.applicationContext as? App ?: return
        val prefs = LibraryAutoDownloadPreferences(app)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastCheckMs() < MIN_CHECK_INTERVAL_MS) return
        if (!prefs.likedEnabled() && !prefs.mixesEnabled()) return

        // No controller yet means the app is still starting; the next call will catch it.
        val controller = app.controller ?: return
        if (!controller.offlineDownloads.supported) return

        scope.launch {
            prefs.setLastCheckMs(now)
            if (prefs.likedEnabled()) {
                runCatching { syncLiked(app, prefs) }
                    .onFailure { Log.e(TAG, "liked auto-pin failed", it) }
            }
            if (prefs.mixesEnabled()) {
                runCatching { syncMixes(app, prefs) }
                    .onFailure { Log.e(TAG, "daily mix auto-pin failed", it) }
            }
        }
    }

    /**
     * Pin the newest of Liked Songs, and let go of the ones that have fallen out of the window.
     *
     * Reads the local `liked_tracks` table rather than the Web API: library sync already keeps it
     * current, it is ordered the way the user's library is ordered, and it works on a check that
     * fires while the phone has a poor connection — which is most of them.
     */
    private suspend fun syncLiked(app: App, prefs: LibraryAutoDownloadPreferences) {
        val controller = app.controller ?: return
        val limit = prefs.likedLimit()
        if (limit <= 0) return

        val liked = controller.likedTracksForPlayback(fromIndex = 0).take(limit)
        if (liked.isEmpty()) return

        val db = PhonoDatabase.get(app)
        val trackDao = db.downloadedTrackDao()
        val collections = db.downloadedCollectionDao()

        val pinned = trackDao.getAll()
            .filter { DownloadStates.isActive(it.state) || it.state == DownloadStates.COMPLETED }
            .map { it.uri }
            .toSet()

        val wanted = AutoPinPlan.likedToPin(
            likedUrisNewestFirst = liked.map { it.uri },
            alreadyPinned = pinned,
            limit = limit,
        )

        if (wanted.isNotEmpty()) {
            val byUri = liked.associateBy { it.uri }
            controller.offlineDownloads.downloadCollection(
                context = app,
                collectionUri = LIKED_COLLECTION_URI,
                type = "playlist",
                name = "Liked Songs",
                artUrl = liked.firstOrNull()?.artUrl,
                tracks = wanted.mapNotNull { byUri[it] },
                quality = controller.downloadQualityApiValue(),
            )
            Log.i(TAG, "queued ${wanted.size} liked track(s)")
        }

        // Drop what has fallen out of the window, but only tracks this feature is responsible for:
        // a track that also belongs to an album or playlist the user pinned by hand stays, because
        // the membership count says someone else still wants it.
        val ownedByLiked = collections.trackUrisForCollection(LIKED_COLLECTION_URI).toSet()
        val stale = AutoPinPlan.likedToDrop(
            likedUrisNewestFirst = liked.map { it.uri },
            pinnedForLiked = ownedByLiked,
            limit = limit,
        )
        for (uri in stale) {
            if (collections.membershipCountForTrack(uri) > 1) continue
            val row = trackDao.getByUri(uri) ?: continue
            controller.offlineDownloads.remove(
                app,
                TrackMetadata(
                    uri = row.uri,
                    title = row.title,
                    artists = row.artists,
                    album = row.album,
                    durationMs = row.duration_ms,
                    artUrl = row.art_url,
                ),
                row.quality,
            )
        }
        if (stale.isNotEmpty()) Log.i(TAG, "dropped ${stale.size} liked track(s) out of window")
    }

    /**
     * Pin the Daily Mixes whose contents have changed since the last check.
     *
     * A changed mix is removed and re-added rather than diffed. The mixes share very few tracks in
     * practice, the collection is small, and a diff would have to reason about tracks that moved
     * between mixes — which is exactly where an automatic deleter should not be clever.
     */
    private suspend fun syncMixes(app: App, prefs: LibraryAutoDownloadPreferences) {
        val controller = app.controller ?: return
        val mixes = controller.dailyMixes()
        if (mixes.isEmpty()) return

        val db = PhonoDatabase.get(app)
        val collections = db.downloadedCollectionDao()
        val chosen = AutoPinPlan.mixesToPin(mixes.map { it.id }, prefs.mixLimit())

        for (mix in mixes.filter { it.id in chosen }) {
            val detail = runCatching { controller.playlistDetail(mix.id) }.getOrNull() ?: continue
            val tracks = detail.tracks.mapNotNull { it.track?.toMetadata() }
            if (tracks.isEmpty()) continue

            val collectionUri = "phono:auto:mix:${mix.id}"
            val pinned = collections.trackUrisForCollection(collectionUri).toSet()
            if (!AutoPinPlan.mixChanged(tracks.map { it.uri }, pinned)) continue

            if (pinned.isNotEmpty()) {
                controller.offlineDownloads.removeCollection(app, collectionUri)
            }
            controller.offlineDownloads.downloadCollection(
                context = app,
                collectionUri = collectionUri,
                type = "playlist",
                name = mix.name,
                artUrl = detail.detail.images?.firstOrNull()?.url,
                tracks = tracks,
                quality = controller.downloadQualityApiValue(),
            )
            Log.i(TAG, "refreshed mix ${mix.name} (${tracks.size} tracks)")
        }
    }
}
