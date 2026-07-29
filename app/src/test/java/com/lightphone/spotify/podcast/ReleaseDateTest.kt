package com.lightphone.spotify.podcast

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ReleaseDateTest {

    private val today = LocalDate.of(2026, 7, 29)

    @Before
    fun fixLocale() {
        // Month names come from the default locale, so pin it or this passes only in the US.
        Locale.setDefault(Locale.US)
    }

    @Test
    fun thisWeekIsRelative() {
        assertEquals("Today", ReleaseDate.human("2026-07-29", "day", today))
        assertEquals("Yesterday", ReleaseDate.human("2026-07-28", "day", today))
        assertEquals("3 days ago", ReleaseDate.human("2026-07-26", "day", today))
        assertEquals("6 days ago", ReleaseDate.human("2026-07-23", "day", today))
    }

    @Test
    fun olderThanAWeekBecomesADate() {
        assertEquals("22 Jul", ReleaseDate.human("2026-07-22", "day", today))
        assertEquals("1 Jan", ReleaseDate.human("2026-01-01", "day", today))
    }

    @Test
    fun anotherYearKeepsItsYear() {
        assertEquals("14 Mar 2024", ReleaseDate.human("2024-03-14", "day", today))
    }

    @Test
    fun scheduledEpisodesReadAsPlainDates() {
        assertEquals("3 Aug", ReleaseDate.human("2026-08-03", "day", today))
    }

    @Test
    fun coarsePrecisionIsNotInvented() {
        assertEquals("Jul 2026", ReleaseDate.human("2026-07", "month", today))
        assertEquals("Jul 2026", ReleaseDate.human("2026-07-12", "month", today))
        assertEquals("2019", ReleaseDate.human("2019", "year", today))
        assertEquals("2019", ReleaseDate.human("2019-04-02", "year", today))
    }

    @Test
    fun missingPrecisionFallsBackThroughTheFormats() {
        assertEquals("Today", ReleaseDate.human("2026-07-29", null, today))
        assertEquals("Jul 2026", ReleaseDate.human("2026-07", null, today))
        assertEquals("2019", ReleaseDate.human("2019", null, today))
    }

    @Test
    fun nothingUsableIsNoSubtitle() {
        assertNull(ReleaseDate.human(null, "day", today))
        assertNull(ReleaseDate.human("", "day", today))
        assertNull(ReleaseDate.human("   ", "day", today))
    }

    @Test
    fun anUnparseableDateIsShownAsGiven() {
        assertEquals("someday", ReleaseDate.human("someday", "day", today))
    }
}
