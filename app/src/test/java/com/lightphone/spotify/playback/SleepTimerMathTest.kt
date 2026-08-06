package com.lightphone.spotify.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepFadeTest {

    @Test
    fun `full volume before the fade starts`() {
        assertEquals(1f, SleepFade.gainAt(60_000L), 0f)
        assertEquals(1f, SleepFade.gainAt(SleepFade.FADE_MS), 0f)
    }

    @Test
    fun `silent at and past the deadline`() {
        assertEquals(0f, SleepFade.gainAt(0L), 0f)
        assertEquals(0f, SleepFade.gainAt(-5_000L), 0f)
    }

    @Test
    fun `halfway through the fade is quieter than half volume in loudness terms`() {
        // sqrt(0.5) — a straight line would still be at 0.5 amplitude here, which is heard as
        // barely quieter at all.
        assertEquals(0.707f, SleepFade.gainAt(10_000L), 0.001f)
    }

    @Test
    fun `never increases as time runs out`() {
        var previous = 1f
        for (remaining in 20_000 downTo 0 step 250) {
            val gain = SleepFade.gainAt(remaining.toLong())
            assertTrue("gain rose at ${remaining}ms", gain <= previous + 1e-6f)
            previous = gain
        }
        assertEquals(0f, previous, 0f)
    }

    @Test
    fun `is inaudible in the last moment`() {
        assertTrue(SleepFade.gainAt(200L) < 0.11f)
    }

    @Test
    fun `a zero-length fade is a hard stop`() {
        assertEquals(1f, SleepFade.gainAt(1L, fadeMs = 0L), 0f)
        assertEquals(0f, SleepFade.gainAt(0L, fadeMs = 0L), 0f)
    }
}

class SleepClockTest {

    @Test
    fun `rounds up so a fresh timer reads as the duration chosen`() {
        assertEquals("30:00", SleepClock.formatRemaining(30 * 60 * 1000L))
        assertEquals("30:00", SleepClock.formatRemaining(30 * 60 * 1000L - 1))
    }

    @Test
    fun `pads seconds`() {
        assertEquals("1:05", SleepClock.formatRemaining(65_000L))
        assertEquals("0:01", SleepClock.formatRemaining(1L))
        assertEquals("0:00", SleepClock.formatRemaining(0L))
    }

    @Test
    fun `negative never shows a minus`() {
        assertEquals("0:00", SleepClock.formatRemaining(-90_000L))
    }

    @Test
    fun `an hour and a half grows an hours field`() {
        assertEquals("1:30:00", SleepClock.formatRemaining(90 * 60 * 1000L))
    }
}

class EndOfItemDelayTest {

    @Test
    fun `resolves to the time left in the track`() {
        assertEquals(120_000L, endOfItemDelayFrom(positionMs = 60_000L, durationMs = 180_000L))
    }

    @Test
    fun `no duration means the option cannot be offered`() {
        assertNull(endOfItemDelayFrom(positionMs = 10_000L, durationMs = 0L))
    }

    @Test
    fun `nothing left to wait for`() {
        assertNull(endOfItemDelayFrom(positionMs = 180_000L, durationMs = 180_000L))
        assertNull(endOfItemDelayFrom(positionMs = 200_000L, durationMs = 180_000L))
    }

    @Test
    fun `a faster episode ends sooner`() {
        // Twenty minutes left at 1.5x is thirteen minutes and twenty seconds of listening.
        assertEquals(800_000L, endOfItemDelayFrom(0L, 1_200_000L, speed = 1.5f))
    }

    @Test
    fun `an impossible rate is treated as normal speed rather than dividing by nothing`() {
        assertEquals(1_200_000L, endOfItemDelayFrom(0L, 1_200_000L, speed = 0f))
    }
}
