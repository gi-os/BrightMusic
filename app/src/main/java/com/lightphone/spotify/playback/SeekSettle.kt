package com.lightphone.spotify.playback

/**
 * Whether a seek has actually landed yet.
 *
 * A seek is asynchronous. For up to about a second after the call returns, the engine is still
 * reporting the *pre-seek* position, and the sink's pending-output correction drags that value further
 * down while the buffer refills — to zero, if the buffer holds more audio than the position is long.
 *
 * Reporting either one moves the progress bar back off where the user just put it. Worse, it is the
 * value the resume stores write, which is why scrubbing and then locking the phone could come back at
 * 0:00. So the controller keeps reporting the target until this says the engine has caught up.
 *
 * Pure and separate from the controller because it is the whole of the fix, and because getting it
 * wrong is silent — the symptom is a position that is subtly stale rather than anything that throws.
 */
internal object SeekSettle {

    /**
     * How far past the target a report may be and still count as the seek having landed.
     *
     * Covers one report interval — the engine reports roughly once a second — plus the sink's
     * pending-output correction, which is up to about three quarters of a second.
     */
    const val TOLERANCE_MS = 1_500L

    /** After this long, accept whatever the engine says even if the seek never landed. */
    const val TIMEOUT_MS = 5_000L

    /**
     * Whether [reportedMs] is plausibly the result of a seek to [targetMs], [elapsedMs] ago.
     *
     * Bounded on both sides, not just `reported >= target`. A one-sided test would accept a stale
     * *forward* position the instant the user seeks backwards — seek from 30:00 to 10:00 and the engine's
     * next report is still 30:00, which passes `>= target` and would be taken as having landed.
     *
     * The upper bound is the wall time since the seek: playback cannot have advanced further than that,
     * so anything beyond it is still the old position.
     */
    fun hasLanded(reportedMs: Long, targetMs: Long, elapsedMs: Long): Boolean =
        reportedMs >= targetMs - TOLERANCE_MS &&
            reportedMs <= targetMs + elapsedMs.coerceAtLeast(0L) + TOLERANCE_MS

    /** Whether to stop waiting and trust the engine again. */
    fun isExpired(elapsedMs: Long): Boolean = elapsedMs > TIMEOUT_MS
}
