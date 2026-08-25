package com.lightphone.spotify.podcast

import android.util.Log
import com.lightphone.spotify.App

/**
 * One cheap question a day, per followed show: what is the newest episode?
 *
 * The dot on a shows list has to be there before you open the show, which means the phone has to
 * know something about a feed it is not looking at. [PodcastAutoDownload] already learns exactly
 * that — but only for shows with auto-download turned on, and most followed shows do not have it.
 * So this walks every followed show and records the newest episode of each; [Unheard.showsWithUnheard]
 * turns that plus the played set into the dots.
 *
 * **The newest episode, one page of one.** `/shows/{id}/episodes?limit=1` is the smallest honest
 * answer to "is there anything new here", and it costs one small request per followed show per day
 * — which is why it rides the daily alarm rather than running on app start or on every visit to the
 * list. A count of unheard episodes would mean paging entire back catalogues on a schedule, for a
 * number nobody asked for.
 *
 * Failures are swallowed per show. A market restriction, a deleted show or a network that dropped
 * halfway through leaves that show's last known answer in place, which is a stale dot at worst; the
 * alternative is one bad show costing every other show its mark.
 */
object UnheardProbe {

    private const val TAG = "UnheardProbe"

    /** How many followed shows to ask about in one pass. */
    private const val MAX_SHOWS = 60

    suspend fun refresh(app: App, prefs: PodcastPreferences) {
        val controller = app.controller ?: return
        val shows = mutableListOf<String>()
        var offset = 0
        while (shows.size < MAX_SHOWS) {
            val page = runCatching { controller.savedShowsPage(offset) }.getOrNull() ?: break
            if (page.items.isEmpty()) break
            page.items.forEach { saved -> shows += saved.show.id }
            offset += page.items.size
            if (offset >= page.total) break
        }
        if (shows.isEmpty()) return

        for (showId in shows) {
            val page = runCatching {
                controller.showEpisodesPage(showId, offset = 0, limit = 1)
            }.getOrNull() ?: continue
            // Newest first is what this endpoint returns, and the default sort the app reads it in.
            val newest = page.items.firstOrNull() ?: continue
            if (!newest.isStreamable) continue
            prefs.setNewestEpisode(showId, newest.uri)
        }
        // A show you unfollowed keeps its stored answer otherwise, and the row is gone anyway — but
        // the file is not, and it grows a key per show forever.
        prefs.forgetNewestExcept(shows.toSet())
        Log.i(TAG, "probed ${shows.size} followed show(s)")
    }
}
