package com.lightphone.spotify.playback

/**
 * What happens at the seam between two tracks.
 *
 * ### Why this is a fade and not a crossfade
 * A crossfade needs two decoders running at once. This app has one: librespot's `Player`, feeding
 * one `AudioTrack` through one ring buffer. Nothing in that chain can hold two tracks at the same
 * instant, and adding a second engine would mean a second Spotify session. So what is offered is
 * the honest half of the effect — the outgoing track fades down into the boundary and the incoming
 * one fades up out of it. There is no overlap, and on a fast transition you can hear that there
 * isn't. It is still the thing people want it for: shuffled tracks stop arriving as a hard cut.
 *
 * ### Why it needs gapless
 * The two halves only meet if the seam is tight. librespot's gapless mode is what makes it tight —
 * it preloads the next track and never stops the sink between them. With gapless off there is a
 * pause in the middle of the fade, which sounds worse than no fade at all. So a fade above zero
 * forces gapless on for as long as it is set; see [effectiveGapless].
 */
object TrackFade {

    /** Nothing. The default, because a fade ruins an album that was mixed to run together. */
    const val OFF_SECONDS: Int = 0

    /** Long enough to be a transition, short enough not to eat a chorus. */
    const val MAX_SECONDS: Int = 12

    /** The lengths the picker steps through. */
    val CHOICES: List<Int> = listOf(0, 2, 4, 6, 8, 10, 12)

    fun sanitize(seconds: Int): Int = seconds.coerceIn(OFF_SECONDS, MAX_SECONDS)

    /**
     * The next value up the wheel, wrapping at the top so one control can reach every option.
     */
    fun next(seconds: Int): Int {
        val index = CHOICES.indexOfFirst { it >= sanitize(seconds) }
        return CHOICES[(if (index < 0) 0 else index + 1) % CHOICES.size]
    }

    fun previous(seconds: Int): Int {
        val index = CHOICES.indexOfFirst { it >= sanitize(seconds) }
        return CHOICES[((if (index < 0) 0 else index) - 1 + CHOICES.size) % CHOICES.size]
    }

    fun label(seconds: Int): String =
        if (sanitize(seconds) <= 0) "Off" else "${sanitize(seconds)}s"

    /**
     * Half the fade sits on each side of the boundary, so the number the user picked is the length
     * of the whole transition rather than of one of its halves. Six seconds means three seconds of
     * the old track going and three of the new one arriving.
     */
    fun halfMs(seconds: Int): Long = (sanitize(seconds) * 1000L) / 2L

    /**
     * Gapless as the player should actually be configured, given both settings.
     *
     * The user's gapless toggle is honoured whenever no fade is set. With a fade set it is
     * overridden to on, because the fade depends on it — see the class doc. That is the whole of
     * the "crossfade wins" rule: one setting silently changes the other, in one direction, for one
     * reason, and the settings screen says so.
     */
    fun effectiveGapless(userGapless: Boolean, fadeSeconds: Int): Boolean =
        sanitize(fadeSeconds) > 0 || userGapless

    /**
     * The gain for a track [positionMs] into a [durationMs]-long item.
     *
     * The two halves are one expression: the smaller of "how far in are we" and "how much is left".
     * On any track longer than the fade the middle is 1.0 and only the ends move, and on a track
     * shorter than the fade the two ramps meet part-way and it never reaches full volume — which is
     * the correct behaviour for a 4-second interlude with a 12-second fade set, and the reason the
     * maximum is 12 and not 60.
     *
     * `sqrt` on each side for the same reason as the sleep fade: equal-power halves, so the dip in
     * the middle of the transition is as shallow as it can be without an overlap.
     *
     * [hasNext] false leaves the tail alone. The last track of a queue is not transitioning into
     * anything, and fading it out would turn every album's closing note into a mistake.
     */
    fun gainAt(
        positionMs: Long,
        durationMs: Long,
        halfMs: Long,
        hasNext: Boolean,
    ): Float {
        if (halfMs <= 0L) return 1f
        val position = positionMs.coerceAtLeast(0L)
        val fadeIn = if (position >= halfMs) 1f else (position.toDouble() / halfMs).toFloat()
        val fadeOut = when {
            !hasNext -> 1f
            durationMs <= 0L -> 1f
            else -> {
                val remaining = durationMs - position
                if (remaining >= halfMs) 1f else (remaining.coerceAtLeast(0L).toDouble() / halfMs).toFloat()
            }
        }
        val linear = minOf(fadeIn, fadeOut).coerceIn(0f, 1f)
        return Math.sqrt(linear.toDouble()).toFloat()
    }
}
