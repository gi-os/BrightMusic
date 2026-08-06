package com.lightphone.spotify.playback

import com.lightphone.spotify.playback.OfflineHandoff.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether a stall becomes downloaded audio.
 *
 * Pinned because this went wrong twice: the handover was gated on the connectivity flag alone, and in
 * a dead zone that flag never flips — nothing is "lost", the radio just stops carrying data.
 */
class OfflineHandoffTest {

    private fun decide(
        stalledForMs: Long,
        networkOnline: Boolean,
        alreadyAsked: Boolean = false,
        currentTrackDownloaded: Boolean = false,
    ) =
        OfflineHandoff.decide(
            stalledForMs = stalledForMs,
            networkOnline = networkOnline,
            alreadyAsked = alreadyAsked,
            currentTrackDownloaded = currentTrackDownloaded,
            bufferingThresholdMs = BUFFERING,
            localHandoffThresholdMs = HANDOFF,
        )

    @Test
    fun `a short stall is just a rebuffer`() {
        assertEquals(Action.Wait, decide(stalledForMs = 1_000, networkOnline = true))
        assertEquals(Action.Wait, decide(stalledForMs = 1_000, networkOnline = false))
        assertEquals(Action.Wait, decide(stalledForMs = BUFFERING, networkOnline = false))
    }

    @Test
    fun `known offline switches as soon as it stalls and reports if nothing is downloaded`() {
        assertEquals(Action.SwitchAndReport, decide(stalledForMs = BUFFERING + 1, networkOnline = false))
    }

    /** The dead-zone case: the flag still says online, and waiting forever is the old bug. */
    @Test
    fun `a long stall switches even when the flag still claims online`() {
        assertEquals(Action.SwitchQuietly, decide(stalledForMs = HANDOFF + 1, networkOnline = true))
    }

    /** Between the two thresholds, a connection we believe in gets the benefit of the doubt. */
    @Test
    fun `a medium stall while believed online keeps waiting`() {
        assertEquals(Action.Wait, decide(stalledForMs = BUFFERING + 1, networkOnline = true))
        assertEquals(Action.Wait, decide(stalledForMs = HANDOFF, networkOnline = true))
    }

    /**
     * A downloaded track never waits out the 15s. The subway case: signal flaps, so the flag can be
     * anything at the moment the buffer runs dry, and the file has been on disk the whole time.
     */
    @Test
    fun `a downloaded track hands off as soon as it stalls`() {
        assertEquals(
            Action.SwitchQuietly,
            decide(stalledForMs = BUFFERING + 1, networkOnline = true, currentTrackDownloaded = true),
        )
        assertEquals(
            Action.SwitchQuietly,
            decide(stalledForMs = BUFFERING + 1, networkOnline = false, currentTrackDownloaded = true),
        )
    }

    /** Downloaded or not, a rebuffer this short is still just a rebuffer. */
    @Test
    fun `a downloaded track still ignores a short stall`() {
        assertEquals(
            Action.Wait,
            decide(stalledForMs = 1_000, networkOnline = true, currentTrackDownloaded = true),
        )
    }

    /** One attempt per stall, or a queue with nothing downloaded re-raises the error every poll. */
    @Test
    fun `it only asks once per stall`() {
        assertEquals(
            Action.Wait,
            decide(stalledForMs = HANDOFF + 1, networkOnline = false, alreadyAsked = true),
        )
        assertEquals(
            Action.Wait,
            decide(stalledForMs = HANDOFF + 1, networkOnline = true, alreadyAsked = true),
        )
    }

    private companion object {
        const val BUFFERING = 8_000L
        const val HANDOFF = 15_000L
    }
}
