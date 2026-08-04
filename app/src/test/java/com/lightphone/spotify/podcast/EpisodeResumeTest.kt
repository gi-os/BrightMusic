package com.lightphone.spotify.podcast

import com.lightphone.spotify.podcast.EpisodeResume.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pinned because both edges of this rule have shipped as "podcasts always start from 0:00".
 */
class EpisodeResumeTest {

    private fun decide(positionMs: Long, durationMs: Long) =
        EpisodeResume.decide(positionMs, durationMs, TAIL, FLOOR)

    @Test
    fun `a position mid-episode is saved`() {
        assertEquals(Outcome.Save, decide(positionMs = 10 * 60_000, durationMs = 60 * 60_000))
    }

    @Test
    fun `the last minute counts as finished`() {
        assertEquals(
            Outcome.ClearFinished,
            decide(positionMs = 60 * 60_000 - 1, durationMs = 60 * 60_000),
        )
    }

    @Test
    fun `the first fifteen seconds are not worth resuming`() {
        assertEquals(Outcome.ClearTooEarly, decide(positionMs = FLOOR, durationMs = 60 * 60_000))
        assertEquals(Outcome.ClearTooEarly, decide(positionMs = 0, durationMs = 60 * 60_000))
    }

    /**
     * The regression that caused the report. A duration shorter than the tail makes
     * `durationMs - finishedTailMs` negative, so *every* position looked finished and the entry was
     * deleted on every single save. Downloaded episodes had their length under-reported by ~3x, so a
     * real 40-minute episode could report 13 minutes — and anything reporting under a minute wiped
     * unconditionally.
     */
    @Test
    fun `a duration shorter than the tail never counts as finished`() {
        assertEquals(Outcome.Save, decide(positionMs = 30_000, durationMs = 45_000))
        assertEquals(Outcome.Save, decide(positionMs = 59_000, durationMs = TAIL))
    }

    /** An unknown duration says nothing about the end, so it must not clear. */
    @Test
    fun `an unknown duration still saves`() {
        assertEquals(Outcome.Save, decide(positionMs = 5 * 60_000, durationMs = 0))
    }

    private companion object {
        const val TAIL = 60_000L
        const val FLOOR = 15_000L
    }
}
