package com.lightphone.spotify.playback

/**
 * When a spinner that has produced no audio should be given up on, and what to say.
 *
 * Extracted for the same reason [OfflineHandoff] was: the rule has now been wrong twice while it
 * lived inline in the stall watchdog, and both times the symptom was a player that span forever
 * over a file sitting on disk.
 *
 * - The first version `continue`d on `isLoading`, so the flag that meant "stuck" also switched off
 *   the only thing that could recover from being stuck.
 * - The second version watched `isLoading` alone. But Rust fans one `PlayerEvent::Loading` out into
 *   `on_buffering(true)`, `on_loading()` and `on_track_changed(uri)`, and the third of those clears
 *   `isLoading` and marks the playback pulse. What that leaves — buffering, not playing, pulse
 *   seen — matched neither arm of the watchdog, so nothing owned the spinner and only an event that
 *   could no longer arrive would have cleared it.
 *
 * The lesson both times is the same: **watch the condition the screen is drawing, not the flag you
 * happened to set.** The ring in `PlayingScreen` is `isLoading || isBuffering`; so is [spinning].
 */
object StuckLoad {

    /** What asking the engine for downloaded audio produced. */
    enum class Handoff {
        /** Downloaded audio is now playing. */
        Switched,

        /** The engine looked, and nothing in the queue has a copy on disk. */
        NothingDownloaded,

        /**
         * The engine could not be asked, or had no Active to answer with.
         *
         * Kept apart from [NothingDownloaded] because "no" and "no answer" are different facts and
         * this codebase has conflated them twice. A `false` returned during a rebuild is the window
         * the rebuild opened, not a statement about the library.
         */
        NoAnswer,
        ;

        override fun toString() = when (this) {
            Switched -> "switched"
            NothingDownloaded -> "nothing downloaded"
            NoAnswer -> "no answer"
        }
    }

    /**
     * Whether the screen is showing a wait that has produced no audio.
     *
     * `isBuffering` on its own is not enough: mid-playback rebuffering is also buffering, and that
     * belongs to the stall watchdog's other arm, which knows how long audio has been dry. The
     * `!isPlaying` is what separates "this never started" from "this stopped".
     */
    fun spinning(isLoading: Boolean, isBuffering: Boolean, isPlaying: Boolean): Boolean =
        isLoading || (isBuffering && !isPlaying)

    /**
     * Whether the wait has been answered, and the spinner should come down.
     *
     * Not automatic, and this is the part worth being careful about. Downloaded audio now playing,
     * no connection at all, or a file on disk the engine would not start are all definite: waiting
     * longer cannot change any of them. Online with nothing downloaded is not definite — that is
     * what a slow load looks like from in here, and taking the spinner away to show an error would
     * replace a wait that was going to succeed with a sentence that is untrue.
     */
    fun definite(handoff: Handoff, believedOffline: Boolean, downloaded: Boolean): Boolean =
        handoff == Handoff.Switched || believedOffline || downloaded

    /**
     * The one line the screen gets, or null to say nothing.
     *
     * A track that is on disk and would not start is not the same failure as one that was never
     * downloaded. Telling someone their downloaded album is "not available offline" sends them to
     * re-download a file they already have — which is the advice that wastes a subway ride.
     */
    fun message(handoff: Handoff, downloaded: Boolean): String? = when {
        handoff == Handoff.Switched -> null
        downloaded -> "Couldn't start that track."
        else -> "Not available offline."
    }
}
