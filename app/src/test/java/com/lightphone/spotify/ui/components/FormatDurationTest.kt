package com.lightphone.spotify.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `formatDuration` is shared by track rows, the player and episode subtitles.
 *
 * It grew an hours branch for podcasts, so these pin down both that the songs case is unchanged and
 * that an hour-plus episode no longer reads as a three-digit minute count.
 */
class FormatDurationTest {

    private fun ms(minutes: Long, seconds: Long = 0) = (minutes * 60 + seconds) * 1000

    @Test
    fun `songs are unchanged`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:07", formatDuration(ms(0, 7)))
        assertEquals("3:05", formatDuration(ms(3, 5)))
        assertEquals("59:59", formatDuration(ms(59, 59)))
    }

    @Test
    fun `an hour or more reads as hours`() {
        // The case this was written for: 108 minutes used to print "108:03".
        assertEquals("1:48:03", formatDuration(ms(108, 3)))
        assertEquals("1:00:00", formatDuration(ms(60)))
        assertEquals("2:05:09", formatDuration(ms(125, 9)))
    }

    @Test
    fun `minutes and seconds stay two digits past an hour`() {
        assertEquals("1:00:05", formatDuration(ms(60, 5)))
        assertEquals("1:09:00", formatDuration(ms(69)))
    }

    @Test
    fun `sub-second values truncate rather than rounding up`() {
        // Position is polled once a second; rounding up would show a duration the track never reaches.
        assertEquals("0:00", formatDuration(999))
        assertEquals("0:01", formatDuration(1_999))
    }

    @Test
    fun `a negative position cannot produce a negative clock`() {
        // seekBy clamps, but the player also renders positions straight from the engine, which reports
        // a brief negative while a seek settles.
        assertEquals("0:00", formatDuration(-5_000))
    }

    @Test
    fun `formatTime matches formatDuration`() {
        assertEquals(formatDuration(ms(108, 3)), formatTime(ms(108, 3)))
    }
}
