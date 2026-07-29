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
import com.lightphone.spotify.ffi.TrackMetadata
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
 * The alarm uses `setAndAllowWhileIdle`, which is the only scheduling call that fires in Doze. A
 * foreground service keeps a process *alive*, not *awake*, so it is no substitute here. Android
 * clamps these to roughly once every 15 minutes at best; a daily cadence is far inside that.
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

        val shows = prefs.autoDownloadShows()
        if (shows.isEmpty()) return

        // No controller yet means the app is still starting; the next call will catch it.
        if (app.controller == null) return
        scope.launch {
            prefs.setLastCheckMs(now)
            for (showId in shows) {
                runCatching { downloadNewEpisodes(app, showId, prefs) }
                    .onFailure { Log.w(TAG, "auto-download failed for $showId", it) }
            }
        }
        // Keep the daily alarm alive: it is cancelled by a reboot or an app update.
        schedule(app)
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
        }.filter { it.isPlayable }

        if (newOnes.isEmpty()) {
            // Still record the newest id, so a show whose latest episode is unplayable in this market
            // does not get re-examined every single check.
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
            artUrl = show?.listArtUrl,
            tracks = newOnes.map { it.toTrackMetadata(show?.name) },
            quality = controller.downloadQualityApiValue(),
        )
        prefs.setLastSeenEpisode(showId, newest.id)
        Log.i(TAG, "queued ${newOnes.size} new episode(s) for $showId")
    }

    /** Daily, inexact, and allowed to fire in Doze — see the class doc. */
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
            alarms.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + DAILY_MS,
                intent,
            )
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
        artUrl = artUrl,
    )

    /** First time a show is enabled, grab a couple rather than its whole back catalogue. */
    private const val FIRST_RUN_EPISODES = 2

    /** Ceiling per check, so a show that dumped a season overnight cannot fill the phone. */
    private const val MAX_PER_CHECK = 5

    private const val REQUEST_CODE = 8021
}

/** Wakes up once a day and asks [PodcastAutoDownload] to look for new episodes. */
class PodcastAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // force: the whole point of the alarm is that it is the scheduled check, so the
        // "was one recent?" guard must not veto it.
        PodcastAutoDownload.checkNow(context, force = true)
        PodcastAutoDownload.schedule(context)
    }
}
