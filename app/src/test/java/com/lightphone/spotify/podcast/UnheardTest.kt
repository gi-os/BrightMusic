package com.lightphone.spotify.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnheardTest {

    @Test
    fun `never touched is unheard`() {
        assertEquals(
            EpisodeMark.Unheard,
            Unheard.markFor(played = false, resumeMs = 0L, playable = true),
        )
        assertTrue(Unheard.dotted(played = false, resumeMs = 0L, playable = true))
    }

    @Test
    fun `a resume position is not a dot`() {
        // The row already says "22 min left". Two answers to one question is worse than one.
        assertEquals(
            EpisodeMark.Started,
            Unheard.markFor(played = false, resumeMs = 60_000L, playable = true),
        )
        assertFalse(Unheard.dotted(played = false, resumeMs = 60_000L, playable = true))
    }

    @Test
    fun `played wins over a stale resume position`() {
        // markPlayed clears the resume position, but a hand-marked episode and a crash mid-write
        // can leave both set, and "played" is the stronger fact.
        assertEquals(
            EpisodeMark.Heard,
            Unheard.markFor(played = true, resumeMs = 60_000L, playable = true),
        )
    }

    @Test
    fun `nothing unplayable is ever dotted`() {
        assertFalse(Unheard.dotted(played = false, resumeMs = 0L, playable = false))
    }

    @Test
    fun `a show is dotted by its newest episode`() {
        val newest = mapOf(
            "show:a" to "spotify:episode:a1",
            "show:b" to "spotify:episode:b1",
            "show:c" to "spotify:episode:c1",
        )
        val dotted = Unheard.showsWithUnheard(
            newestByShow = newest,
            played = setOf("spotify:episode:b1"),
            resumeMsOf = { uri -> if (uri == "spotify:episode:c1") 30_000L else 0L },
        )
        assertEquals(setOf("show:a"), dotted)
    }

    @Test
    fun `a show nobody has probed yet is not dotted`() {
        assertEquals(
            emptySet<String>(),
            Unheard.showsWithUnheard(mapOf("show:a" to ""), emptySet()) { 0L },
        )
        assertEquals(
            emptySet<String>(),
            Unheard.showsWithUnheard(emptyMap(), emptySet()) { 0L },
        )
    }
}
