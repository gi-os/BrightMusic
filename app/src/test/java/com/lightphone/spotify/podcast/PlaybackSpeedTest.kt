package com.lightphone.spotify.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun `the cycle starts at normal and returns to it`() {
        assertEquals(PlaybackSpeed.NORMAL, PlaybackSpeed.CYCLE.first())
        var speed = PlaybackSpeed.NORMAL
        repeat(PlaybackSpeed.CYCLE.size) { speed = PlaybackSpeed.next(speed) }
        assertTrue(PlaybackSpeed.isSame(PlaybackSpeed.NORMAL, speed))
    }

    @Test
    fun `the cycle goes faster before it goes slower`() {
        // Nearly everyone who changes a podcast's speed raises it, so the first tap must speed up.
        assertTrue(PlaybackSpeed.next(PlaybackSpeed.NORMAL) > PlaybackSpeed.NORMAL)
    }

    @Test
    fun `every speed in the cycle is one the sink will accept`() {
        PlaybackSpeed.CYCLE.forEach { speed ->
            assertTrue("$speed out of range", speed >= PlaybackSpeed.MIN && speed <= PlaybackSpeed.MAX)
        }
    }

    @Test
    fun `an unknown speed lands back on the start of the cycle`() {
        assertEquals(PlaybackSpeed.CYCLE.first(), PlaybackSpeed.next(1.37f))
    }

    @Test
    fun `labels drop trailing zeros`() {
        assertEquals("1x", PlaybackSpeed.label(1.0f))
        assertEquals("2x", PlaybackSpeed.label(2.0f))
        assertEquals("1.5x", PlaybackSpeed.label(1.5f))
        assertEquals("1.2x", PlaybackSpeed.label(1.2f))
        assertEquals("1.75x", PlaybackSpeed.label(1.75f))
        assertEquals("0.8x", PlaybackSpeed.label(0.8f))
    }

    @Test
    fun `every speed in the cycle has a label that fits the control`() {
        PlaybackSpeed.CYCLE.forEach { speed ->
            val label = PlaybackSpeed.label(speed)
            assertTrue("$label too long", label.length <= 5)
        }
    }

    @Test
    fun `a stored value from another build cannot strand playback`() {
        assertEquals(PlaybackSpeed.NORMAL, PlaybackSpeed.sanitize(0f))
        assertEquals(PlaybackSpeed.NORMAL, PlaybackSpeed.sanitize(-1f))
        assertEquals(PlaybackSpeed.NORMAL, PlaybackSpeed.sanitize(Float.NaN))
        assertEquals(PlaybackSpeed.MAX, PlaybackSpeed.sanitize(4f))
        assertEquals(PlaybackSpeed.MIN, PlaybackSpeed.sanitize(0.1f))
        // Anything already valid is left exactly alone.
        assertEquals(1.5f, PlaybackSpeed.sanitize(1.5f))
    }
}
