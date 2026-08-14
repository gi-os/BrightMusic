package com.lightphone.spotify.radio.recognize

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GapDetectorTest {

    private val rate = 44_100

    private fun tone(seconds: Double, amplitude: Double): ShortArray {
        val n = (seconds * rate).toInt()
        return ShortArray(n) { i ->
            (sin(2 * PI * 440 * i / rate) * amplitude * 32767).toInt().toShort()
        }
    }

    private fun silence(seconds: Double): ShortArray = ShortArray((seconds * rate).toInt())

    @Test
    fun `a window that fades to nothing ends in silence`() {
        val pcm = tone(10.0, 0.5) + silence(2.0)
        assertTrue(GapDetector.tailIsSilent(pcm, rate))
    }

    @Test
    fun `music to the last sample is not a boundary`() {
        assertFalse(GapDetector.tailIsSilent(tone(12.0, 0.5), rate))
    }

    @Test
    fun `a quiet talking level is not silence`() {
        // −30dBFS — a soft-spoken DJ, well above the −40 threshold.
        val pcm = tone(10.0, 0.5) + tone(2.0, 0.0316)
        assertFalse(GapDetector.tailIsSilent(pcm, rate))
    }

    @Test
    fun `a beat's rest inside the window does not count, only the tail`() {
        val pcm = tone(5.0, 0.5) + silence(1.0) + tone(6.0, 0.5)
        assertFalse(GapDetector.tailIsSilent(pcm, rate))
    }

    @Test
    fun `a window shorter than the tail cannot answer`() {
        assertFalse(GapDetector.tailIsSilent(silence(0.5), rate))
    }
}
