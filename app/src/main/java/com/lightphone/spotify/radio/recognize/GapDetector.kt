package com.lightphone.spotify.radio.recognize

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Song boundaries out of the audio itself: a stretch of near-silence between tracks.
 *
 * Gio's observation — "songs are marked once there's a gap in the music" — used as a *hint*, not
 * a marker. College radio crossfades and DJs talk over intros, so silence proves nothing on its
 * own; but a sampled window that fades out at its end very likely ends on a transition, and the
 * next recognition attempt is worth taking soon rather than after the full throttle interval.
 *
 * Pure maths over PCM so it can be tested with synthesised audio; see `GapDetectorTest`.
 */
object GapDetector {

    /** −40dBFS: quiet room on a broadcast chain. A talking DJ sits far above this. */
    const val SILENCE_THRESHOLD_DB = -40.0

    /** Long enough that a beat's rest or a word gap does not read as a track boundary. */
    const val TAIL_MS = 1500

    /** Whether the last [tailMs] of [pcm] sit below the silence threshold. */
    fun tailIsSilent(
        pcm: ShortArray,
        sampleRateHz: Int,
        tailMs: Int = TAIL_MS,
        thresholdDb: Double = SILENCE_THRESHOLD_DB,
    ): Boolean {
        val tailSamples = sampleRateHz * tailMs / 1000
        if (tailSamples <= 0 || pcm.size < tailSamples) return false
        return rmsDb(pcm, pcm.size - tailSamples, pcm.size) < thresholdDb
    }

    /** RMS level of [pcm] between [from] and [to], in dBFS. Digital silence floors at −120. */
    fun rmsDb(pcm: ShortArray, from: Int, to: Int): Double {
        var sum = 0.0
        var count = 0
        for (i in from.coerceAtLeast(0) until to.coerceAtMost(pcm.size)) {
            val v = pcm[i] / 32768.0
            sum += v * v
            count++
        }
        if (count == 0) return -120.0
        val rms = sqrt(sum / count)
        return if (rms <= 1e-6) -120.0 else 20 * log10(rms)
    }
}
