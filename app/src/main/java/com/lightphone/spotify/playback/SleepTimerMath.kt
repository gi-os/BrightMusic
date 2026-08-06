package com.lightphone.spotify.playback

import kotlin.math.roundToLong

/**
 * The arithmetic behind the sleep timer, kept away from Android so it can be tested on the JVM.
 *
 * Everything here is a pure function of a duration in milliseconds. The alarm, the wake lock and the
 * sinks it turns down live in [SleepTimer].
 */
object SleepFade {

    /**
     * How long the fade to silence takes.
     *
     * Twenty seconds because the point of the feature is not to be woken by it. A hard stop at the
     * end of a track is a change in the room; a fade this long is not noticed by someone already
     * asleep, and someone still awake has time to reach for the phone and add fifteen minutes.
     */
    const val FADE_MS: Long = 20_000L

    /**
     * Gain to apply with [remainingMs] left before the timer expires.
     *
     * `sqrt` rather than a straight line: perceived loudness runs closer to the square root of
     * amplitude, so a linear ramp sounds like it holds its level and then drops off a cliff in the
     * last second. This one is heard as an even decline. It reaches 0.32 at the halfway point and
     * 0.1 with 200ms to go, which is inaudible over a pillow.
     */
    fun gainAt(remainingMs: Long, fadeMs: Long = FADE_MS): Float {
        if (fadeMs <= 0L) return if (remainingMs <= 0L) 0f else 1f
        if (remainingMs <= 0L) return 0f
        if (remainingMs >= fadeMs) return 1f
        val x = remainingMs.toDouble() / fadeMs.toDouble()
        return Math.sqrt(x).toFloat().coerceIn(0f, 1f)
    }
}

object SleepClock {

    /**
     * What the now-playing screen says is left.
     *
     * Rounds up, so a timer set for 30 minutes reads "30:00" for its first second rather than
     * "29:59" — the number the user just chose should be the number they see. Under a minute it
     * counts seconds, because that is the part worth watching.
     */
    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = ((remainingMs.coerceAtLeast(0L)).toDouble() / 1000.0).let {
            Math.ceil(it).roundToLong()
        }
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes >= 60) {
            val hours = minutes / 60
            "%d:%02d:%02d".format(hours, minutes % 60, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}

/**
 * When "end of track" — or "end of episode", the same thing said about a podcast — actually lands.
 *
 * Returns a delay in milliseconds from now, or null when there is nothing with a length playing:
 * a live radio stream, or a track whose duration the engine has not reported yet. A null is the
 * caller's cue to leave the option out of the list rather than arm a timer that would fire
 * immediately.
 *
 * [speed] is the podcast playback rate. An episode with twenty minutes left at 1.5x ends in
 * thirteen and a third, and a sleep timer that ignored that would cut the last seven minutes off.
 */
fun endOfItemDelayFrom(positionMs: Long, durationMs: Long, speed: Float = 1f): Long? {
    if (durationMs <= 0L) return null
    val remaining = durationMs - positionMs.coerceAtLeast(0L)
    if (remaining <= 0L) return null
    val rate = if (speed > 0.05f) speed.toDouble() else 1.0
    return (remaining / rate).roundToLong().coerceAtLeast(0L)
}
