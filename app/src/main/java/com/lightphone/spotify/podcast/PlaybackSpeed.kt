package com.lightphone.spotify.podcast

/**
 * The speeds an episode can be played at, and the order tapping walks through them.
 *
 * A fixed cycle rather than a slider or a menu: this is a one-handed control on a small panel, the
 * useful range is narrow, and a list of five values is reachable in at most four taps from anywhere
 * in it. Starting at 1.0 and going up first is deliberate — nearly everyone who changes the speed of
 * a podcast raises it, and the two slow values sit at the end of the cycle where they are still
 * reachable but not in the way.
 *
 * `AudioTrack` handles the resampling with sonic, which preserves pitch, so 1.5x is faster speech
 * rather than a chipmunk. Values are kept inside 0.5–2.0 because that is the range sonic is good at;
 * past 2x the artefacts are audible and past 0.5x it sounds submerged.
 *
 * No Android imports, so the cycle and the labels can be unit tested on the host.
 */
object PlaybackSpeed {
    const val NORMAL = 1.0f

    /** Tap order. */
    val CYCLE = listOf(1.0f, 1.2f, 1.5f, 1.75f, 2.0f, 0.8f)

    val MIN = 0.5f
    val MAX = 2.0f

    /** The next speed after [current], wrapping. Unknown values land on the start of the cycle. */
    fun next(current: Float): Float {
        val index = CYCLE.indexOfFirst { isSame(it, current) }
        if (index < 0) return CYCLE.first()
        return CYCLE[(index + 1) % CYCLE.size]
    }

    /**
     * What the button says.
     *
     * Trailing zeros are dropped so the common speeds read as "1x" and "2x" rather than "1.00x",
     * which is three characters of nothing on a panel where the control row is already tight.
     */
    fun label(speed: Float): String {
        val rounded = Math.round(speed * 100f) / 100f
        val text = when {
            isSame(rounded, Math.round(rounded).toFloat()) -> Math.round(rounded).toString()
            (rounded * 10f) % 1f == 0f -> trimZero(rounded, 1)
            else -> trimZero(rounded, 2)
        }
        return "${text}x"
    }

    /** Clamp anything arriving from preferences or an old install into a speed the sink will accept. */
    fun sanitize(speed: Float): Float =
        if (speed.isNaN() || speed <= 0f) NORMAL else speed.coerceIn(MIN, MAX)

    /** Float equality, at the only precision these values are ever stored to. */
    fun isSame(a: Float, b: Float): Boolean = Math.abs(a - b) < 0.001f

    private fun trimZero(value: Float, decimals: Int): String {
        val text = String.format("%.${decimals}f", value)
        return text.trimEnd('0').trimEnd('.')
    }
}
