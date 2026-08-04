package com.lightphone.spotify.podcast

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** How many downloaded episodes to keep per show. */
enum class PodcastRetention(val keep: Int, val label: String) {
    Keep3(3, "Keep 3"),
    Keep5(5, "Keep 5"),

    /** Nothing is ever pruned; the user manages downloads themselves. */
    Never(Int.MAX_VALUE, "Never delete"),
    ;

    companion object {
        val DEFAULT = Keep3

        fun fromKey(key: String?): PodcastRetention = entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * Podcast state that has to outlive a process: which shows auto-download, how many episodes to keep,
 * where you were in an episode, and which episodes have already been seen.
 *
 * All in SharedPreferences rather than Room, deliberately. Adding entities would mean bumping the
 * Room version, and `PhonoDatabase` uses `fallbackToDestructiveMigration()`, so it would wipe the
 * user's downloaded music. None of this is relational or large enough to justify that risk, and
 * everything that genuinely needs to survive a journey — the audio itself — is already in the
 * existing downloads tables.
 */
object PodcastSettings {

    var retention: PodcastRetention by mutableStateOf(PodcastRetention.DEFAULT)
        private set

    fun setRetention(prefs: PodcastPreferences, value: PodcastRetention) {
        retention = value
        prefs.setRetention(value)
    }

    /** Show ids set to download new episodes automatically. */
    var autoDownloadShows: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * Whether episode lists read oldest-first.
     *
     * One setting for every show rather than one per show: it is a reading habit — you either work
     * through a back catalogue from the beginning or you follow a feed — and a per-show version would
     * be a preference the user has to set again for each new subscription.
     */
    var episodesOldestFirst: Boolean by mutableStateOf(false)
        private set

    fun setEpisodesOldestFirst(prefs: PodcastPreferences, value: Boolean) {
        episodesOldestFirst = value
        prefs.setEpisodesOldestFirst(value)
    }

    /**
     * Playback rate for episodes.
     *
     * One rate for all podcasts rather than one per show, for the same reason [episodesOldestFirst]
     * is global: it is a listening habit, not a property of a feed, and a per-show setting is one
     * more thing to set again every time you subscribe to something.
     *
     * Music is deliberately left at 1x and has no control — an album is mixed at a tempo and playing
     * it faster is not a thing anyone asked for. The rate is applied when an episode loads and
     * reset when anything else does; see `PlaybackController.applyPlaybackSpeedFor`.
     */
    var episodeSpeed: Float by mutableStateOf(PlaybackSpeed.NORMAL)
        private set

    fun setEpisodeSpeed(prefs: PodcastPreferences, value: Float) {
        val clean = PlaybackSpeed.sanitize(value)
        episodeSpeed = clean
        prefs.setEpisodeSpeed(clean)
    }

    /** Advance to the next rate in the cycle and persist it. Returns the new rate. */
    fun cycleEpisodeSpeed(prefs: PodcastPreferences): Float {
        val next = PlaybackSpeed.next(episodeSpeed)
        setEpisodeSpeed(prefs, next)
        return next
    }

    /**
     * Episodes proven to have no Spotify-hosted audio. Observable so a row greys out the moment the
     * download that discovered it gives up, rather than at the next cold start.
     */
    var unplayableEpisodes: Set<String> by mutableStateOf(emptySet())
        private set

    fun markUnplayable(prefs: PodcastPreferences, uri: String) {
        if (uri in unplayableEpisodes) return
        unplayableEpisodes = unplayableEpisodes + uri
        prefs.addUnplayableEpisode(uri)
    }

    fun isUnplayable(uri: String): Boolean = uri in unplayableEpisodes

    fun load(prefs: PodcastPreferences) {
        autoDownloadShows = prefs.autoDownloadShows()
        retention = prefs.retention()
        episodesOldestFirst = prefs.episodesOldestFirst()
        episodeSpeed = prefs.episodeSpeed()
        unplayableEpisodes = prefs.unplayableEpisodes()
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

    fun retention(): PodcastRetention = PodcastRetention.fromKey(prefs.getString(KEY_RETENTION, null))

    fun setRetention(value: PodcastRetention) {
        prefs.edit().putString(KEY_RETENTION, value.name).apply()
    }

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
        if (positionMs <= EpisodeResume.RESUME_FLOOR_MS) {
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

    fun episodesOldestFirst(): Boolean = prefs.getBoolean(KEY_OLDEST_FIRST, false)

    fun setEpisodesOldestFirst(value: Boolean) {
        prefs.edit().putBoolean(KEY_OLDEST_FIRST, value).apply()
    }

    /** Sanitized on the way out as well as in, so a value written by an older build cannot strand
     * playback at a rate the sink will refuse. */
    fun episodeSpeed(): Float =
        PlaybackSpeed.sanitize(prefs.getFloat(KEY_EPISODE_SPEED, PlaybackSpeed.NORMAL))

    fun setEpisodeSpeed(value: Float) {
        prefs.edit().putFloat(KEY_EPISODE_SPEED, PlaybackSpeed.sanitize(value)).apply()
    }

    /**
     * Episodes of [showId] the user downloaded deliberately, by long-press or by ticking them.
     *
     * Retention is a rule about the episodes auto-download fetched on its own, so these are exempt:
     * without that, ticking twenty episodes of a show set to "Keep 3" would download all twenty and
     * delete seventeen of them at the next daily check — the app quietly undoing what it was just
     * asked to do.
     *
     * Add-only. A uri left behind after its download is removed exempts nothing, so the cost of not
     * pruning this set is a few dozen bytes rather than a wrong decision.
     */
    fun keptEpisodes(showId: String): Set<String> =
        prefs.getStringSet(keptKey(showId), emptySet())?.toSet().orEmpty()

    fun addKeptEpisodes(showId: String, uris: Collection<String>) {
        if (uris.isEmpty()) return
        // A copy, for the same reason setAutoDownloadShows makes one.
        prefs.edit().putStringSet(keptKey(showId), HashSet(keptEpisodes(showId) + uris)).apply()
    }

    /**
     * Episodes a download proved unplayable, by uri.
     *
     * Written when the native downloader comes back with "no playable file", which means Spotify has
     * no audio file of its own for that episode — it is served from the publisher's feed. The next
     * attempt would fail exactly the same way, so the row is greyed instead, the same as an episode
     * Spotify will not stream to this market. Uris rather than ids because that is what both the
     * downloader and the player speak.
     */
    fun unplayableEpisodes(): Set<String> =
        prefs.getStringSet(KEY_UNPLAYABLE, emptySet())?.toSet().orEmpty()

    fun addUnplayableEpisode(uri: String) {
        if (uri.isBlank()) return
        prefs.edit().putStringSet(KEY_UNPLAYABLE, HashSet(unplayableEpisodes() + uri)).apply()
    }

    fun keptBackfillDone(): Boolean = prefs.getBoolean(KEY_KEPT_BACKFILL, false)

    fun setKeptBackfillDone() {
        prefs.edit().putBoolean(KEY_KEPT_BACKFILL, true).apply()
    }

    /** Last time the background check ran, to avoid hammering the API on every cold start. */
    fun lastCheckMs(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun setLastCheckMs(value: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK, value).apply()
    }

    private fun resumeKey(episodeUri: String) = "resume:$episodeUri"

    private fun seenKey(showId: String) = "seen:$showId"

    private fun keptKey(showId: String) = "kept:$showId"

    private companion object {
        const val PREFS_NAME = "phono_podcasts"
        const val KEY_AUTO_SHOWS = "auto_download_shows"
        const val KEY_LAST_CHECK = "last_check_ms"
        const val KEY_RETENTION = "retention"
        const val KEY_OLDEST_FIRST = "episodes_oldest_first"
        const val KEY_EPISODE_SPEED = "episode_speed"
        const val KEY_UNPLAYABLE = "unplayable_episodes"
        const val KEY_KEPT_BACKFILL = "kept_backfill_done"

    }
}
