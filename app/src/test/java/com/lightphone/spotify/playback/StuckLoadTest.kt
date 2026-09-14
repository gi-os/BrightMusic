package com.lightphone.spotify.playback

import com.lightphone.spotify.playback.StuckLoad.Handoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StuckLoadTest {

    // --- spinning -----------------------------------------------------------

    @Test
    fun `a fresh tap that has not loaded yet is spinning`() {
        assertTrue(StuckLoad.spinning(isLoading = true, isBuffering = false, isPlaying = false))
    }

    /**
     * The regression this whole file exists for. One `PlayerEvent::Loading` from Rust arrives as
     * `on_buffering(true)`, `on_loading()`, `on_track_changed(uri)` — and the last of those clears
     * `isLoading`. Watching that flag alone left this exact state unowned, and the ring in
     * `PlayingScreen` stayed up until the process was killed.
     */
    @Test
    fun `buffering with no playback is spinning even once isLoading has been cleared`() {
        assertTrue(StuckLoad.spinning(isLoading = false, isBuffering = true, isPlaying = false))
    }

    @Test
    fun `rebuffering mid-track is not spinning - it belongs to the stall watchdog`() {
        assertFalse(StuckLoad.spinning(isLoading = false, isBuffering = true, isPlaying = true))
    }

    @Test
    fun `ordinary playback is not spinning`() {
        assertFalse(StuckLoad.spinning(isLoading = false, isBuffering = false, isPlaying = true))
    }

    @Test
    fun `idle is not spinning`() {
        assertFalse(StuckLoad.spinning(isLoading = false, isBuffering = false, isPlaying = false))
    }

    // --- definite -----------------------------------------------------------

    @Test
    fun `a successful handoff is definite`() {
        assertTrue(
            StuckLoad.definite(Handoff.Switched, believedOffline = false, downloaded = false),
        )
    }

    @Test
    fun `no connection is definite - there is nothing left to wait for`() {
        assertTrue(
            StuckLoad.definite(
                Handoff.NothingDownloaded,
                believedOffline = true,
                downloaded = false,
            ),
        )
    }

    @Test
    fun `a downloaded track that would not start is definite`() {
        assertTrue(
            StuckLoad.definite(
                Handoff.NothingDownloaded,
                believedOffline = false,
                downloaded = true,
            ),
        )
    }

    @Test
    fun `a downloaded track the engine could not answer about is still definite`() {
        assertTrue(
            StuckLoad.definite(Handoff.NoAnswer, believedOffline = false, downloaded = true),
        )
    }

    /**
     * The case that must keep waiting. Online, nothing on disk — indistinguishable from here from a
     * load that is merely slow, and cutting it short would show an error about a wait that was
     * going to work.
     */
    @Test
    fun `online with nothing downloaded keeps waiting`() {
        assertFalse(
            StuckLoad.definite(
                Handoff.NothingDownloaded,
                believedOffline = false,
                downloaded = false,
            ),
        )
        assertFalse(
            StuckLoad.definite(Handoff.NoAnswer, believedOffline = false, downloaded = false),
        )
    }

    // --- message ------------------------------------------------------------

    @Test
    fun `a successful handoff says nothing - the audio is the answer`() {
        assertNull(StuckLoad.message(Handoff.Switched, downloaded = true))
        assertNull(StuckLoad.message(Handoff.Switched, downloaded = false))
    }

    @Test
    fun `a downloaded track is never described as not available offline`() {
        assertEquals(
            "Couldn't start that track.",
            StuckLoad.message(Handoff.NothingDownloaded, downloaded = true),
        )
        assertEquals(
            "Couldn't start that track.",
            StuckLoad.message(Handoff.NoAnswer, downloaded = true),
        )
    }

    @Test
    fun `nothing downloaded and nothing loading says so`() {
        assertEquals(
            "Not available offline.",
            StuckLoad.message(Handoff.NothingDownloaded, downloaded = false),
        )
    }

    // --- the strings that go in the report ----------------------------------

    @Test
    fun `handoff names are stable - reports are read months later`() {
        assertEquals("switched", Handoff.Switched.toString())
        assertEquals("nothing downloaded", Handoff.NothingDownloaded.toString())
        assertEquals("no answer", Handoff.NoAnswer.toString())
    }
}
