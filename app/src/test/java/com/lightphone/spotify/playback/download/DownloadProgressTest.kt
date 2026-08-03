package com.lightphone.spotify.playback.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun `percent is null until a total is known`() {
        assertNull(DownloadProgress(fetchedBytes = 0, totalBytes = 0).percent)
        assertNull(DownloadProgress(fetchedBytes = 1024, totalBytes = 0).percent)
    }

    @Test
    fun `percent is clamped to the range a bar can draw`() {
        assertEquals(0, DownloadProgress(0, 100).percent)
        assertEquals(50, DownloadProgress(50, 100).percent)
        assertEquals(100, DownloadProgress(100, 100).percent)
        // A total that turns out to be short of what arrived should not draw past the end.
        assertEquals(100, DownloadProgress(120, 100).percent)
    }

    @Test
    fun `the first report of a download always passes`() {
        val throttle = DownloadProgressThrottle()
        assertTrue(throttle.shouldReport("a", DownloadProgress(1, 100), nowMs = 0))
    }

    @Test
    fun `reports below the step and inside the interval are dropped`() {
        val throttle = DownloadProgressThrottle(percentStep = 5, minIntervalMs = 1_000)
        assertTrue(throttle.shouldReport("a", DownloadProgress(0, 100), nowMs = 0))
        assertFalse(throttle.shouldReport("a", DownloadProgress(2, 100), nowMs = 100))
        assertFalse(throttle.shouldReport("a", DownloadProgress(4, 100), nowMs = 200))
        assertTrue(throttle.shouldReport("a", DownloadProgress(5, 100), nowMs = 300))
    }

    @Test
    fun `a slow chunk still refreshes once the interval has passed`() {
        val throttle = DownloadProgressThrottle(percentStep = 50, minIntervalMs = 1_000)
        assertTrue(throttle.shouldReport("a", DownloadProgress(0, 100), nowMs = 0))
        assertFalse(throttle.shouldReport("a", DownloadProgress(1, 100), nowMs = 500))
        assertTrue(throttle.shouldReport("a", DownloadProgress(2, 100), nowMs = 1_100))
    }

    @Test
    fun `arriving at 100 always passes so the bar does not stop short`() {
        val throttle = DownloadProgressThrottle(percentStep = 90, minIntervalMs = 1_000_000)
        assertTrue(throttle.shouldReport("a", DownloadProgress(0, 100), nowMs = 0))
        assertFalse(throttle.shouldReport("a", DownloadProgress(50, 100), nowMs = 1))
        assertTrue(throttle.shouldReport("a", DownloadProgress(100, 100), nowMs = 2))
    }

    @Test
    fun `downloads are throttled independently of each other`() {
        val throttle = DownloadProgressThrottle(percentStep = 5, minIntervalMs = 1_000)
        assertTrue(throttle.shouldReport("a", DownloadProgress(0, 100), nowMs = 0))
        // b has never reported, so its first one passes even though a just did.
        assertTrue(throttle.shouldReport("b", DownloadProgress(0, 100), nowMs = 0))
        assertFalse(throttle.shouldReport("a", DownloadProgress(1, 100), nowMs = 10))
    }

    @Test
    fun `forgetting a download lets the next one start over`() {
        val throttle = DownloadProgressThrottle(percentStep = 5, minIntervalMs = 1_000)
        assertTrue(throttle.shouldReport("a", DownloadProgress(0, 100), nowMs = 0))
        assertFalse(throttle.shouldReport("a", DownloadProgress(1, 100), nowMs = 10))
        throttle.forget("a")
        assertTrue(throttle.shouldReport("a", DownloadProgress(1, 100), nowMs = 20))
    }

    @Test
    fun `notification text names the track and counts what is behind it`() {
        assertEquals(
            "Kid A — 42%",
            downloadNotificationText("Kid A", DownloadProgress(42, 100), remaining = 0),
        )
        assertEquals(
            "Kid A — 42% (+3 queued)",
            downloadNotificationText("Kid A", DownloadProgress(42, 100), remaining = 3),
        )
    }

    @Test
    fun `notification text falls back before the first chunk lands`() {
        assertEquals(
            "Preparing…",
            downloadNotificationText(null, null, remaining = 0),
        )
        // A title but no total yet: name it, but do not invent a percentage.
        assertEquals(
            "Kid A",
            downloadNotificationText("Kid A", DownloadProgress(0, 0), remaining = 0),
        )
    }
}
