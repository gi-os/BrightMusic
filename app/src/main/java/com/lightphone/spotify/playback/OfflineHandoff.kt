package com.lightphone.spotify.playback

/**
 * When a stall should be answered with downloaded audio, and when the user should be told.
 *
 * Extracted from `PlaybackController`'s stall watchdog so the rule can be tested. It is worth pinning:
 * the handover was gated purely on "Kotlin says the connection is gone", and in a dead zone Kotlin is
 * never told — the radio stays registered, so `onLost` does not fire. Playback stopped when the
 * read-ahead ran out with a downloaded copy of the same track on disk.
 */
object OfflineHandoff {

    /** What the watchdog should do about a stall this long. */
    enum class Action {
        /** Keep waiting — a normal rebuffer. */
        Wait,

        /**
         * Try downloaded audio and, if there is none, tell the user they are offline. Used when the
         * connection is known to be gone, where there is nothing to wait for.
         */
        SwitchAndReport,

        /**
         * Try downloaded audio, but stay quiet if there is none. Used when we still believe we are
         * online: the stall is long enough to be worth acting on, but "not available offline" would
         * be the wrong thing to say about slow data.
         */
        SwitchQuietly,
    }

    /**
     * @param stalledForMs how long audio has been dry.
     * @param networkOnline what the connectivity flag currently claims.
     * @param alreadyAsked whether a handover has been attempted for this stall already.
     * @param currentTrackDownloaded whether the stalled track has a completed download on disk.
     */
    fun decide(
        stalledForMs: Long,
        networkOnline: Boolean,
        alreadyAsked: Boolean,
        currentTrackDownloaded: Boolean,
        bufferingThresholdMs: Long,
        localHandoffThresholdMs: Long,
    ): Action = when {
        stalledForMs <= bufferingThresholdMs -> Action.Wait
        alreadyAsked -> Action.Wait
        // The file is on disk. There is nothing a longer wait can produce that the pin does not
        // already have, whatever the connectivity flag says — and the flag is least trustworthy in
        // exactly this situation. Do not spend the 15s local-handoff wait on it.
        currentTrackDownloaded -> Action.SwitchQuietly
        !networkOnline -> Action.SwitchAndReport
        stalledForMs > localHandoffThresholdMs -> Action.SwitchQuietly
        else -> Action.Wait
    }
}
