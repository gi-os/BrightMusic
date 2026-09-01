package com.lightphone.spotify.podcast

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.lightphone.spotify.App
import com.lightphone.spotify.data.webapi.SpotifyEpisode
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.DownloadedTrackEntity
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.playback.download.DownloadStates
import com.lightphone.spotify.playback.download.SpotifyDownloadCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Downloads new podcast episodes so there is always something to listen to with no signal.
 *
 * ### When it runs
 * Two triggers, because neither alone is enough:
 *
 *  - **App start.** Cheap, and catches the common case — you open the app on Wi-Fi before leaving.
 *  - **A daily alarm.** So an episode that lands overnight is on the phone before the morning
 *    commute, without the app having been opened.
 *
 * The alarm is an `...AndAllowWhileIdle` one, which is the only kind that fires in Doze. A
 * foreground service keeps a process *alive*, not *awake*, so it is no substitute here. And it is
 * the *exact* flavour, not because a daily check needs punctuality but because only an exact alarm
 * puts the app on the exemption list that lets the receiver start the download service from the
 * background — an inexact alarm fired, the receiver ran, and the service start was refused,
 * silently, every night. See [schedule].
 *
 * ### What it downloads
 * Only shows the user turned on, and only episodes newer than the last one this phone has seen —
 * compared by **id, not release date**, since a date says when an episode was published rather than
 * whether it has already been fetched, and a show publishing twice in a day would otherwise skip one.
 *
 * Downloads go through the existing [com.lightphone.spotify.playback.download.OfflineDownloadCenter],
 * so episodes land in the same tables as music and show up in Downloads with everything else. That is
 * also why podcasts needed no schema change.
 */
object PodcastAutoDownload {
    private const val TAG = "PodcastAutoDownload"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Skip the start-up check if one ran recently; opening the app repeatedly should be free. */
    private const val MIN_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    private const val DAILY_MS = 24L * 60 * 60 * 1000

    /**
     * Check now if it has been a while. Safe to call on every cold start.
     *
     * [force] skips the interval check, for the pull-to-refresh style manual trigger.
     */
    fun checkNow(context: Context, force: Boolean = false) {
        val app = context.applicationContext as? App ?: return
        val prefs = PodcastPreferences(app)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastCheckMs() < MIN_CHECK_INTERVAL_MS) return

        // No controller yet means the app is still starting; the next call will catch it.
        if (app.controller == null) return
        scope.launch { runCheck(app) }
        // Keep the daily alarm alive: it is cancelled by a reboot or an app update.
        schedule(app)
    }

    /**
     * The check itself, suspend so the download service can run it under its own wake and Wi-Fi
     * locks on the nightly alarm — the probe alone can be a request per followed show, which is
     * minutes of network a frozen process never finishes. [checkNow] wraps it for the in-app
     * triggers, where the process is awake anyway.
     */
    suspend fun runCheck(context: Context) {
        val app = context.applicationContext as? App ?: return
        if (app.controller == null) return
        val prefs = PodcastPreferences(app)
        prefs.setLastCheckMs(System.currentTimeMillis())
        // Every followed show, not only the auto-download ones: this is what the unheard dot on
        // the shows list is drawn from, and most followed shows never get auto-download turned
        // on. Runs before the downloads because it is the cheap half — one small request per
        // show — and a download that fails should not cost the marks.
        runCatching { UnheardProbe.refresh(app, prefs) }
            .onFailure { Log.w(TAG, "unheard probe failed", it) }
        val shows = prefs.autoDownloadShows()
        if (shows.isEmpty()) return
        // Before anything can prune. See [backfillKeptEpisodes].
        runCatching { backfillKeptEpisodes(app, prefs) }
            .onFailure { Log.e(TAG, "kept-episode backfill failed", it) }
        for (showId in shows) {
            runCatching { downloadNewEpisodes(app, showId, prefs) }
                .onFailure { Log.w(TAG, "auto-download failed for $showId", it) }
        }
    }

    private suspend fun downloadNewEpisodes(
        app: App,
        showId: String,
        prefs: PodcastPreferences,
    ) {
        val controller = app.controller ?: return
        val episodes = controller.showEpisodes(showId)
        if (episodes.isEmpty()) return

        val lastSeen = prefs.lastSeenEpisode(showId)
        // Spotify returns newest first. Everything before the last-seen id is new; if it is not in
        // the page at all (first run, or a long gap) only take the newest few rather than a year of
        // backlog, which would fill the phone.
        val newest = episodes.first()
        val newOnes = when {
            lastSeen == null -> episodes.take(FIRST_RUN_EPISODES)
            else -> episodes.takeWhile { it.id != lastSeen }.take(MAX_PER_CHECK)
        }.filter { it.isStreamable && !PodcastSettings.isUnplayable(it.uri) }

        if (newOnes.isEmpty()) {
            // Still record the newest id, so a show whose latest episode cannot be played here — a
            // market restriction, or audio Spotify never hosted — does not get re-examined every
            // single check.
            prefs.setLastSeenEpisode(showId, newest.id)
            return
        }

        val show = runCatching { controller.show(showId) }.getOrNull()
        controller.offlineDownloads.downloadCollection(
            context = app,
            // Namespaced so it cannot collide with an album or playlist pin of the same id.
            collectionUri = "spotify:show:$showId",
            type = "show",
            name = show?.name ?: "Podcast",
            // Kept on disk and shown at header size in Downloads, so take the big one.
            artUrl = show?.detailArtUrl,
            tracks = newOnes.map { it.toTrackMetadata(show?.name) },
            quality = controller.downloadQualityApiValue(),
        )
        prefs.setLastSeenEpisode(showId, newest.id)
        Log.i(TAG, "queued ${newOnes.size} new episode(s) for $showId")
        prune(app, showId)
    }

    /**
     * One-time: treat everything already downloaded as hand-picked, so retention cannot eat it.
     *
     * The exemption for hand-picked episodes only knows about downloads made after it shipped. Every
     * episode already on the phone predates the record and would look automatic, so the first check
     * after the update would prune a show back to the limit and delete downloads the user had chosen
     * — the exact failure the exemption exists to prevent, happening once, on the way to fixing it.
     *
     * Which is why this errs the other way: everything currently pinned is grandfathered in,
     * automatic or not. Retention then governs only what arrives from here on, which is a rule the
     * user can actually see working.
     */
    private suspend fun backfillKeptEpisodes(app: App, prefs: PodcastPreferences) {
        if (prefs.keptBackfillDone()) return
        val db = PhonoDatabase.get(app)
        val collections = db.downloadedCollectionDao()
        for (collectionUri in collections.collectionUrisOfType("show")) {
            val showId = collectionUri.substringAfterLast(':').takeIf { it.isNotBlank() } ?: continue
            val uris = collections.trackUrisForCollection(collectionUri)
            if (uris.isNotEmpty()) prefs.addKeptEpisodes(showId, uris)
        }
        prefs.setKeptBackfillDone()
        Log.i(TAG, "grandfathered existing podcast downloads out of retention")
    }

    /**
     * Apply the retention limit to every auto-download show now.
     *
     * Called when the setting changes, because otherwise lowering "Keep 5" to "Keep 3" would appear to
     * do nothing until the next episode happened to arrive. Only touches shows with auto-download on:
     * anything downloaded by hand is the user's, and retention is a rule about the automatic ones.
     */
    fun pruneNow(context: Context) {
        val app = context.applicationContext as? App ?: return
        if (PodcastSettings.retention.keep == Int.MAX_VALUE) return
        val shows = PodcastPreferences(app).autoDownloadShows()
        if (shows.isEmpty()) return
        val prefs = PodcastPreferences(app)
        scope.launch {
            runCatching { backfillKeptEpisodes(app, prefs) }
                .onFailure { Log.e(TAG, "kept-episode backfill failed", it) }
            for (showId in shows) {
                runCatching { prune(app, showId) }
                    .onFailure { e -> Log.e(TAG, "prune failed for $showId", e) }
            }
        }
    }

    /**
     * Which of a show's downloaded episodes to drop to get back to [keep].
     *
     * Pure and separate from [prune] because it is the part that deletes the user's audio, and it is
     * worth being able to test that it drops the right rows without a database.
     *
     * Newest-first by `updated_at`, which is when the download last changed rather than when the
     * episode was published — that is the right key here, because what should go is whatever has been
     * sitting on the phone longest, and a back-published episode downloaded today is not stale.
     *
     * In-flight downloads count towards the limit. [prune] runs immediately after enqueueing, so
     * counting completed rows only would leave the just-queued episodes uncounted, the show still at
     * exactly [keep], and nothing pruned until the next daily check — the opposite of what the setting
     * promises. Sorting newest-first also means those in-flight rows are never the ones dropped, so
     * this cannot cancel the download that triggered it.
     *
     * [keptByHand] is outside the rule entirely — it neither counts nor gets dropped. Retention
     * governs what auto-download fetched on its own; an episode the user picked is an instruction,
     * and deleting it a day later would be the app undoing what it was asked to do. See
     * [PodcastPreferences.keptEpisodes].
     */
    internal fun episodesToDrop(
        rows: List<DownloadedTrackEntity>,
        keep: Int,
        keptByHand: Set<String> = emptySet(),
    ): List<DownloadedTrackEntity> {
        if (keep == Int.MAX_VALUE) return emptyList()
        return rows
            // Anything on its way to being playable counts. Failed and already-removing rows do not:
            // they occupy no space to reclaim, and counting them would prune real episodes to make
            // room for rows that will never play.
            .filter { it.state != DownloadStates.FAILED && it.state != DownloadStates.REMOVING }
            .filter { it.uri !in keptByHand }
            .sortedByDescending { it.updated_at }
            .drop(keep)
    }

    /** Delete the episodes [episodesToDrop] picked, audio included. */
    private suspend fun prune(app: App, showId: String) {
        val keep = PodcastSettings.retention.keep
        if (keep == Int.MAX_VALUE) return

        val db = PhonoDatabase.get(app)
        val collections = db.downloadedCollectionDao()
        val tracks = db.downloadedTrackDao()
        val collectionUri = "spotify:show:$showId"

        val rows = episodesToDrop(
            rows = collections.trackUrisForCollection(collectionUri).mapNotNull { tracks.getByUri(it) },
            keep = keep,
            keptByHand = PodcastPreferences(app).keptEpisodes(showId),
        )
        if (rows.isEmpty()) return
        val controller = app.controller ?: return
        for (row in rows) {
            // Membership first, then the audio — the same order `removeCollection` uses. Dropping the
            // file while the row still pointed at it would leave the Downloads screen counting a
            // track it can no longer play.
            collections.deleteMembership(collectionUri, row.uri)
            // An episode pinned individually as well as by the show keeps its audio: the user asked
            // for that copy explicitly, and retention only governs the automatic ones.
            if (collections.membershipCountForTrack(row.uri) > 0) continue
            controller.offlineDownloads.remove(
                app,
                TrackMetadata(
                    uri = row.uri,
                    title = row.title,
                    artists = row.artists,
                    album = row.album,
                    // Only `uri` and `quality` are read by `remove`; the rest is here because the
                    // interface takes metadata rather than a uri.
                    durationMs = 0L,
                    artUrl = row.art_url,
                ),
                row.quality,
            )
        }
        Log.i(TAG, "pruned ${rows.size} old episode(s) from $showId")
    }

    /** Daily, exact, and allowed to fire in Doze — see the class doc. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE,
            Intent(app, PodcastAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            // Exact, not for punctuality: an inexact alarm is not on the exemption list that lets a
            // receiver start a foreground service from the background, so the overnight download
            // service would silently never start. Same pattern as SleepTimer.scheduleAlarm.
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + DAILY_MS,
                    intent,
                )
            } else {
                alarms.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + DAILY_MS,
                    intent,
                )
            }
        }.onFailure { Log.w(TAG, "could not schedule the daily check", it) }
    }

    private fun SpotifyEpisode.toTrackMetadata(showName: String?): TrackMetadata = TrackMetadata(
        uri = uri,
        title = name,
        // The show is the useful "artist" line for an episode, and it is what the player and the
        // downloads list both display.
        artists = showName ?: "Podcast",
        album = showName ?: "Podcast",
        durationMs = durationMs,
        // The widest image, not the list thumbnail: this one is shown full width.
        artUrl = fullArtUrl,
    )

    /** First time a show is enabled, grab a couple rather than its whole back catalogue. */
    private const val FIRST_RUN_EPISODES = 2

    /** Ceiling per check, so a show that dumped a season overnight cannot fill the phone. */
    private const val MAX_PER_CHECK = 5

    private const val REQUEST_CODE = 8021
}

/** Wakes up once a day and hands the nightly check to the download service. */
class PodcastAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Two halves, both load-bearing. The exact alarm is what makes this start legal: only an
        // exact-alarm receiver is exempt from the ban on starting a foreground service from the
        // background. And the foreground service is what keeps the work alive: the check is up to
        // sixty sequential requests, which outlives a receiver's ~10-second grace window many
        // times over — run here, Doze froze it mid-probe every night. The service runs it under
        // the wake and Wi-Fi locks it already holds for drains. One start covers podcasts and the
        // library both; the library checker rides this alarm rather than owning one, because
        // Android clamps setAndAllowWhileIdle to roughly one firing every fifteen minutes per app
        // and both checks want the same moment — overnight, before you leave. Named for podcasts
        // because it was theirs first.
        SpotifyDownloadCenter.startNightlyCheck(context)
        PodcastAutoDownload.schedule(context)
    }
}
