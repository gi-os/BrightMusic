package com.lightphone.spotify.podcast

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Podcast state that has to outlive a process: which shows auto-download, where you were in an
 * episode, and which episodes have already been seen.
 *
 * All in SharedPreferences rather than Room, deliberately. Adding entities would mean bumping the
 * Room version, and `PhonoDatabase` uses `fallbackToDestructiveMigration()`, so it would wipe the
 * user's downloaded music. None of this is relational or large enough to justify that risk, and
 * everything that genuinely needs to survive a journey — the audio itself — is already in the
 * existing downloads tables.
 */
object PodcastSettings {

    /** Show ids set to download new episodes automatically. */
    var autoDownloadShows: Set<String> by mutableStateOf(emptySet())
        private set

    fun load(prefs: PodcastPreferences) {
        autoDownloadShows = prefs.autoDownloadShows()
    }

    fun isAutoDownload(showId: String): Boolean = showId in autoDownloadShows

    fun toggleAutoDownload(prefs: PodcastPreferences, showId: String): Boolean {
        val on = showId !in autoDownloadShows
        autoDownloadShows = if (on) autoDownloadShows + showId else autoDownloadShows - showId
        prefs.setAutoDownloadShows(autoDownloadShows)
        return on
    }
}

class PodcastPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun autoDownloadShows(): Set<String> =
        prefs.getStringSet(KEY_AUTO_SHOWS, emptySet())?.toSet().orEmpty()

    fun setAutoDownloadShows(ids: Set<String>) {
        // A copy, because SharedPreferences keeps a reference to the set it is handed and returning
        // the same instance later makes mutations invisible.
        prefs.edit().putStringSet(KEY_AUTO_SHOWS, HashSet(ids)).apply()
    }

    /**
     * Where playback of [episodeUri] got to. Podcasts are long, so resuming is the difference between
     * usable and not.
     *
     * Kept locally rather than read from Spotify's own `resume_point`, which needs the
     * `user-read-playback-position` scope — that would force every existing user through a
     * re-authorize, and a local position is the one that works on a train with no signal.
     */
    fun resumePosition(episodeUri: String): Long =
        prefs.getLong(resumeKey(episodeUri), 0L)

    fun setResumePosition(episodeUri: String, positionMs: Long) {
        if (positionMs <= RESUME_FLOOR_MS) {
            // Near the start is the same as not started, and storing it would make every accidental
            // tap resume a few seconds in.
            prefs.edit().remove(resumeKey(episodeUri)).apply()
            return
        }
        prefs.edit().putLong(resumeKey(episodeUri), positionMs).apply()
    }

    fun clearResumePosition(episodeUri: String) {
        prefs.edit().remove(resumeKey(episodeUri)).apply()
    }

    /**
     * Newest episode id already known for a show.
     *
     * The auto-downloader compares against this instead of release dates: a date tells you when an
     * episode came out, not whether this phone has seen it, and a show that publishes twice in a day
     * would otherwise have one episode silently skipped.
     */
    fun lastSeenEpisode(showId: String): String? = prefs.getString(seenKey(showId), null)

    fun setLastSeenEpisode(showId: String, episodeId: String) {
        prefs.edit().putString(seenKey(showId), episodeId).apply()
    }

    /** Last time the background check ran, to avoid hammering the API on every cold start. */
    fun lastCheckMs(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun setLastCheckMs(value: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK, value).apply()
    }

    private fun resumeKey(episodeUri: String) = "resume:$episodeUri"

    private fun seenKey(showId: String) = "seen:$showId"

    private companion object {
        const val PREFS_NAME = "phono_podcasts"
        const val KEY_AUTO_SHOWS = "auto_download_shows"
        const val KEY_LAST_CHECK = "last_check_ms"

        /** Below this, treat an episode as unstarted. */
        const val RESUME_FLOOR_MS = 15_000L
    }
}
