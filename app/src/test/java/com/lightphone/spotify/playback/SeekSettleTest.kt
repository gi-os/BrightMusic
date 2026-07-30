package com.lightphone.spotify.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seek-settle decision, which is what stopped a scrub being overwritten by the engine's stale
 * position — the bug where scrubbing and then leaving came back at 0:00.
 */
class SeekSettleTest {

    private fun mins(m: Long) = m * 60_000L

    @Test
    fun `a stale position from before a forward seek has not landed`() {
        // Scrub 10:00 -> 30:00. The engine's next report is still 10:00.
        assertFalse(SeekSettle.hasLanded(reportedMs = mins(10), targetMs = mins(30), elapsedMs = 200))
    }

    @Test
    fun `a stale position from before a backward seek has not landed`() {
        // Scrub 30:00 -> 10:00, engine still reporting 30:00. This is the case a one-sided
        // "reported >= target" test would wrongly accept, since 30:00 is past 10:00.
        assertFalse(SeekSettle.hasLanded(reportedMs = mins(30), targetMs = mins(10), elapsedMs = 200))
    }

    @Test
    fun `the target itself has landed`() {
        assertTrue(SeekSettle.hasLanded(reportedMs = mins(30), targetMs = mins(30), elapsedMs = 0))
    }

    @Test
    fun `a report dragged below the target by the sink correction still lands`() {
        // audiblePositionMs subtracts the sink's pending output, up to about 750ms.
        assertTrue(
            SeekSettle.hasLanded(
                reportedMs = mins(30) - 750,
                targetMs = mins(30),
                elapsedMs = 100,
            ),
        )
    }

    @Test
    fun `a report zeroed by the sink correction has not landed`() {
        // The 0:00 case: near the start, position minus pending output floors at zero.
        assertFalse(SeekSettle.hasLanded(reportedMs = 0, targetMs = mins(30), elapsedMs = 100))
    }

    @Test
    fun `playback advancing while the seek settles still counts as landed`() {
        // Two seconds passed before the report arrived, so the position is legitimately ahead.
        assertTrue(
            SeekSettle.hasLanded(
                reportedMs = mins(30) + 2_000,
                targetMs = mins(30),
                elapsedMs = 2_000,
            ),
        )
    }

    @Test
    fun `a position further ahead than time allows has not landed`() {
        // 20 seconds ahead after 200ms cannot be the result of this seek.
        assertFalse(
            SeekSettle.hasLanded(
                reportedMs = mins(30) + 20_000,
                targetMs = mins(30),
                elapsedMs = 200,
            ),
        )
    }

    @Test
    fun `a seek to the very start lands on a zero report`() {
        // Rewinding to 0 must not be confused with the zeroed-report case above.
        assertTrue(SeekSettle.hasLanded(reportedMs = 0, targetMs = 0, elapsedMs = 100))
    }

    @Test
    fun `negative elapsed cannot widen the window`() {
        // Defensive: a clock that went backwards must not turn into a free pass.
        assertFalse(
            SeekSettle.hasLanded(reportedMs = mins(30), targetMs = mins(10), elapsedMs = -10_000),
        )
    }

    @Test
    fun `the deadline releases a seek that never lands`() {
        assertFalse(SeekSettle.isExpired(0))
        assertFalse(SeekSettle.isExpired(SeekSettle.TIMEOUT_MS))
        assertTrue(SeekSettle.isExpired(SeekSettle.TIMEOUT_MS + 1))
    }
}
