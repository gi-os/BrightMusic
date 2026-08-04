package com.lightphone.spotify.podcast

/**
 * Whether a position is worth remembering for an episode.
 *
 * Extracted so it can be tested. Both of this rule's edges have caused a "podcasts always start from
 * 0:00" report: a position at or under the floor is discarded, and a position inside the tail deletes
 * the entry as finished — which fires for *every* position when the duration is shorter than the tail,
 * and durations were being under-reported for downloaded episodes.
 */
object EpisodeResume {

    /**
     * Below this, treat an episode as unstarted. Lives here rather than in `PodcastPreferences`
     * because this object is the single owner of the save/clear rule, and the store enforcing a
     * different floor from the rule is how the two could disagree.
     */
    const val RESUME_FLOOR_MS = 15_000L

    enum class Outcome {
        /** Store this position. */
        Save,

        /** Forget any stored position: near enough the end to count as finished. */
        ClearFinished,

        /** Forget any stored position: near enough the start that resuming is pointless. */
        ClearTooEarly,
    }

    /**
     * @param positionMs where playback got to.
     * @param durationMs the episode's length, or 0 when unknown.
     * @param finishedTailMs how close to the end counts as finished.
     * @param floorMs positions at or below this are not worth storing.
     */
    fun decide(
        positionMs: Long,
        durationMs: Long,
        finishedTailMs: Long,
        floorMs: Long,
    ): Outcome = when {
        // Only trust the tail when the duration is longer than it. Otherwise `durationMs -
        // finishedTailMs` is <= 0 and every position looks finished, wiping the entry on every save.
        // An unknown (0) duration lands here too, which is right — it says nothing about the end.
        durationMs > finishedTailMs && positionMs > durationMs - finishedTailMs -> Outcome.ClearFinished
        positionMs <= floorMs -> Outcome.ClearTooEarly
        else -> Outcome.Save
    }
}
