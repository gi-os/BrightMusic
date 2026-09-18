package com.lightphone.spotify.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The outbound half: when this phone tells Spotify where it got to, and in what form.
 */
class ResumeReportTest {

    @Test
    fun `positions are rounded down to whole seconds`() {
        // Spotify stores seconds and hands the rounded value back. Sending milliseconds would make
        // its own echo look like another device having nudged the point.
        assertEquals(1_200_000L, ResumeReport.roundToSecond(1_200_999L))
        assertEquals(1_200_000L, ResumeReport.roundToSecond(1_200_000L))
        assertEquals(0L, ResumeReport.roundToSecond(999L))
        assertEquals(0L, ResumeReport.roundToSecond(-5L))
    }

    @Test
    fun `the first report is always due`() {
        assertTrue(ResumeReport.due(lastSentAtMs = 0L, nowMs = 1_000L, force = false))
    }

    @Test
    fun `the periodic save waits out the interval`() {
        val sentAt = 1_000_000L
        assertFalse(ResumeReport.due(sentAt, sentAt + 5_000L, force = false))
        assertTrue(ResumeReport.due(sentAt, sentAt + ResumeReport.MIN_INTERVAL_MS, force = false))
    }

    @Test
    fun `the moments that end listening ignore the interval`() {
        // A pause, an episode change, an episode finished. These are what the desktop is waiting
        // for, and they are rare enough to cost nothing.
        val sentAt = 1_000_000L
        assertTrue(ResumeReport.due(sentAt, sentAt + 1L, force = true))
    }

    @Test
    fun `a clock that went backwards does not park the next report`() {
        // A time zone change or an NTP correction would otherwise hold reports until the phone
        // caught up with a timestamp from its own future.
        assertTrue(ResumeReport.due(lastSentAtMs = 5_000_000L, nowMs = 1_000L, force = false))
    }

    @Test
    fun `a fresh report is settling and an old one is not`() {
        val sentAt = 1_000_000L
        assertTrue(ResumeReport.settling(sentAt, sentAt + 1_000L))
        assertFalse(ResumeReport.settling(sentAt, sentAt + ResumeReport.WRITE_SETTLE_MS))
    }

    @Test
    fun `a phone that has never reported is not settling`() {
        // Zero means "never", not "at the epoch" — and reading it as a report would have every
        // fresh install ignore Spotify's resume points until the window passed.
        assertFalse(ResumeReport.settling(lastSentAtMs = 0L, nowMs = 1_000L))
    }
}
