package com.lightphone.spotify.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFadeSettingTest {

    @Test
    fun `clamped to the offered range`() {
        assertEquals(0, TrackFade.sanitize(-3))
        assertEquals(12, TrackFade.sanitize(30))
        assertEquals(6, TrackFade.sanitize(6))
    }

    @Test
    fun `off by default and labelled as such`() {
        assertEquals("Off", TrackFade.label(0))
        assertEquals("6s", TrackFade.label(6))
    }

    @Test
    fun `the choices wrap`() {
        assertEquals(2, TrackFade.next(0))
        assertEquals(0, TrackFade.next(12))
        assertEquals(12, TrackFade.previous(0))
    }

    @Test
    fun `the number chosen is the length of the whole transition`() {
        assertEquals(3_000L, TrackFade.halfMs(6))
        assertEquals(0L, TrackFade.halfMs(0))
    }
}

class TrackFadePrecedenceTest {

    @Test
    fun `with no fade set the users gapless preference stands`() {
        assertTrue(TrackFade.effectiveGapless(userGapless = true, fadeSeconds = 0))
        assertFalse(TrackFade.effectiveGapless(userGapless = false, fadeSeconds = 0))
    }

    @Test
    fun `a fade wins over gapless being off`() {
        assertTrue(TrackFade.effectiveGapless(userGapless = false, fadeSeconds = 6))
    }

    @Test
    fun `a fade of zero seconds is not a fade`() {
        assertFalse(TrackFade.effectiveGapless(userGapless = false, fadeSeconds = -1))
    }
}

class TrackFadeCurveTest {

    private val half = TrackFade.halfMs(6) // 3000ms each side

    @Test
    fun `no fade set leaves the gain alone`() {
        assertEquals(1f, TrackFade.gainAt(0L, 200_000L, halfMs = 0L, hasNext = true), 0f)
    }

    @Test
    fun `silent at both edges of a transition`() {
        assertEquals(0f, TrackFade.gainAt(0L, 200_000L, half, hasNext = true), 0f)
        assertEquals(0f, TrackFade.gainAt(200_000L, 200_000L, half, hasNext = true), 0f)
    }

    @Test
    fun `full through the middle of a normal track`() {
        assertEquals(1f, TrackFade.gainAt(100_000L, 200_000L, half, hasNext = true), 0f)
        assertEquals(1f, TrackFade.gainAt(half, 200_000L, half, hasNext = true), 0f)
    }

    @Test
    fun `the last track of a queue plays its ending`() {
        assertEquals(1f, TrackFade.gainAt(199_900L, 200_000L, half, hasNext = false), 0f)
        // Its opening still fades in — it arrived from somewhere.
        assertEquals(0f, TrackFade.gainAt(0L, 200_000L, half, hasNext = false), 0f)
    }

    @Test
    fun `an unknown duration cannot fade out`() {
        assertEquals(1f, TrackFade.gainAt(100_000L, 0L, half, hasNext = true), 0f)
    }

    @Test
    fun `rises monotonically over the fade in`() {
        var previous = -1f
        for (position in 0..3_000 step 100) {
            val gain = TrackFade.gainAt(position.toLong(), 200_000L, half, hasNext = true)
            assertTrue("gain fell at ${position}ms", gain >= previous - 1e-6f)
            previous = gain
        }
        assertEquals(1f, previous, 1e-6f)
    }

    @Test
    fun `a track shorter than the fade never reaches full volume`() {
        // Four seconds with a 6-second fade: the two ramps cross at the two-second mark.
        val peak = TrackFade.gainAt(2_000L, 4_000L, half, hasNext = true)
        assertTrue("peak was $peak", peak < 1f)
        assertEquals(0.816f, peak, 0.001f)
    }

    @Test
    fun `equal power halves so the dip in the middle is shallow`() {
        // 500ms before the boundary and 500ms after: each side is sqrt(1/6), and the pair sums to
        // more than 0.8 rather than the 0.33 a linear ramp would leave.
        val out = TrackFade.gainAt(199_500L, 200_000L, half, hasNext = true)
        val into = TrackFade.gainAt(500L, 200_000L, half, hasNext = true)
        assertEquals(out, into, 1e-6f)
        assertTrue("sum was ${out + into}", out + into > 0.8f)
    }
}
