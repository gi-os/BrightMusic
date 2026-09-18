package com.lightphone.spotify.podcast

/**
 * When a podcast position is sent back to Spotify, and in what form.
 *
 * The other half of [EpisodeResumeSync]. That one decides whether to take Spotify's position; this
 * one decides when to give it ours, so that an episode listened to here opens where you left it on
 * the desktop. Pure, and tested, because getting either of its two rules wrong is invisible until
 * the next device is picked up:
 *
 * - **Whole seconds.** Spotify's own clients round, and the backend hands the rounded value back on
 *   the next read. The phone records what it sent as "what Spotify now reports", so sending
 *   milliseconds would have the rounded echo read as another device having moved the point — and
 *   [EpisodeResumeSync] would adopt it, dragging playback back by up to a second on every list load.
 * - **Not on every tick.** The store is written every ten seconds while an episode plays, and a
 *   request per write is a request per ten seconds of listening, per phone, forever. The local
 *   store is what playback uses; Spotify only needs to be roughly current.
 */
object ResumeReport {

    /** Floor between two reports. Moments that end listening ignore it — see [due]. */
    const val MIN_INTERVAL_MS = 30_000L

    /**
     * How long after a report the phone treats Spotify's answer as possibly pre-dating it.
     *
     * An episode list fetched a moment before a report lands carries the old position, and
     * ingesting it afterwards would look exactly like another device having moved the point
     * backwards. Inside this window the local value wins; after it, a difference is real.
     */
    const val WRITE_SETTLE_MS = 30_000L

    /**
     * Cap on the pending queue. Reached only when something has been wrong for a very long time —
     * a queue this long is weeks of listening with no successful write — and a preference file that
     * grows without limit is worse than the oldest few reports being dropped.
     */
    const val MAX_PENDING = 200

    fun roundToSecond(positionMs: Long): Long =
        if (positionMs <= 0L) 0L else (positionMs / 1000L) * 1000L

    /**
     * @param force the moments that end listening — pausing, switching episode, finishing one.
     *   Those are what the other devices are waiting for, and they are rare enough to cost nothing.
     */
    fun due(lastSentAtMs: Long, nowMs: Long, force: Boolean, minIntervalMs: Long = MIN_INTERVAL_MS): Boolean =
        when {
            force -> true
            lastSentAtMs <= 0L -> true
            // A clock that went backwards (time zone, NTP, a reboot without a battery-backed clock)
            // would otherwise park the next report until the phone caught up with itself.
            nowMs < lastSentAtMs -> true
            else -> nowMs - lastSentAtMs >= minIntervalMs
        }

    /** Whether a report is recent enough that Spotify's answer may not include it yet. */
    fun settling(lastSentAtMs: Long, nowMs: Long, settleMs: Long = WRITE_SETTLE_MS): Boolean =
        lastSentAtMs > 0L && nowMs >= lastSentAtMs && nowMs - lastSentAtMs < settleMs
}
