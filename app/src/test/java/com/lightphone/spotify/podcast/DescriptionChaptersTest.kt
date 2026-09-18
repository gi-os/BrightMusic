package com.lightphone.spotify.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chapters written into the show notes — the half of the feature that needs no network call, and
 * the only one that covers non-English shows.
 *
 * The rules are Spotify's, and the tests are mostly about *refusing*: a description full of numbers
 * is the common case, and marks in the wrong places on the scrub bar are worse than no marks.
 */
class DescriptionChaptersTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun `a plain list parses`() {
        val chapters = DescriptionChapters.parse(
            """
            This week we talk about everything.

            00:00 Intro
            04:12 The interview
            48:30 Listener questions
            """.trimIndent(),
            durationMs = hour,
        )
        assertEquals(3, chapters.size)
        assertEquals(Chapter("Intro", 0L, 252_000L), chapters[0])
        assertEquals(Chapter("The interview", 252_000L, 2_910_000L), chapters[1])
        // The last one runs to the end of the episode: there is no next start to close it.
        assertEquals(Chapter("Listener questions", 2_910_000L, hour), chapters[2])
    }

    @Test
    fun `hours, brackets and separators are all read`() {
        val chapters = DescriptionChapters.parse(
            """
            (00:00:00) - Cold open
            [00:12:30] — Part one
            1:05:00: Part two
            """.trimIndent(),
            durationMs = 2 * hour,
        )
        assertEquals(listOf("Cold open", "Part one", "Part two"), chapters.map { it.title })
        assertEquals(3_900_000L, chapters[2].startMs)
    }

    @Test
    fun `a leading index is not part of the title`() {
        val chapters = DescriptionChapters.parse(
            """
            00:00 1 - Introduction
            10:00 2. The guest
            20:00 3) Wrapping up
            """.trimIndent(),
            durationMs = hour,
        )
        assertEquals(listOf("Introduction", "The guest", "Wrapping up"), chapters.map { it.title })
    }

    @Test
    fun `two timestamps are not a chapter list`() {
        // Spotify's own floor, and a good one: two lines with times in them is what a normal
        // description looks like.
        assertTrue(
            DescriptionChapters.parse("00:00 Intro\n10:00 The rest", durationMs = hour).isEmpty(),
        )
    }

    @Test
    fun `a list that does not start at zero is refused`() {
        // The first chapter of an episode is the beginning of the episode. A list starting at 4:12
        // is a set of highlights, and the bar would have no mark for the first four minutes.
        assertTrue(
            DescriptionChapters.parse(
                "04:12 The interview\n20:00 Questions\n40:00 The end",
                durationMs = hour,
            ).isEmpty(),
        )
    }

    @Test
    fun `timestamps closer than thirty seconds are refused`() {
        assertTrue(
            DescriptionChapters.parse(
                "00:00 One\n00:20 Two\n10:00 Three",
                durationMs = hour,
            ).isEmpty(),
        )
    }

    @Test
    fun `timestamps that run past the end are refused`() {
        // Proof these were never this episode's chapters: a list copied from elsewhere, or a
        // duration printed in the text.
        assertTrue(
            DescriptionChapters.parse(
                "00:00 One\n30:00 Two\n90:00 Three",
                durationMs = hour,
            ).isEmpty(),
        )
    }

    @Test
    fun `prose with times in it yields nothing`() {
        assertTrue(
            DescriptionChapters.parse(
                "Recorded at 10:30 on a Tuesday. Call us on 555 0100.",
                durationMs = hour,
            ).isEmpty(),
        )
    }

    @Test
    fun `a timestamp with no title is not a chapter`() {
        // Three bare times with nothing to name them would draw three anonymous marks.
        assertTrue(
            DescriptionChapters.parse("00:00\n10:00\n20:00", durationMs = hour).isEmpty(),
        )
    }

    @Test
    fun `nothing at all is nothing`() {
        assertTrue(DescriptionChapters.parse(null, durationMs = hour).isEmpty())
        assertTrue(DescriptionChapters.parse("", durationMs = hour).isEmpty())
    }

    @Test
    fun `an unknown duration still closes the last chapter`() {
        // Downloaded episodes have reported a zero duration before now, and a chapter whose end is
        // before its start cannot be drawn.
        val chapters = DescriptionChapters.parse(
            "00:00 One\n10:00 Two\n20:00 Three",
            durationMs = 0L,
        )
        assertEquals(3, chapters.size)
        assertTrue(chapters.last().endMs >= chapters.last().startMs)
    }

    @Test
    fun `the chapter at a position is the one that contains it`() {
        val chapters = listOf(
            Chapter("One", 0L, 60_000L),
            Chapter("Two", 60_000L, 120_000L),
            Chapter("Three", 120_000L, 180_000L),
        )
        assertEquals("One", chapters.chapterAt(0L)?.title)
        assertEquals("One", chapters.chapterAt(59_999L)?.title)
        assertEquals("Two", chapters.chapterAt(60_000L)?.title)
        assertEquals("Three", chapters.chapterAt(179_999L)?.title)
        // Past the last chapter's end — an episode longer than its own chapter list.
        assertNull(chapters.chapterAt(200_000L))
        assertNull(emptyList<Chapter>().chapterAt(1_000L))
    }
}
