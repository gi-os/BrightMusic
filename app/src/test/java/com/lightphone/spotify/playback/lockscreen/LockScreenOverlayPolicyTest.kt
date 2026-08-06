package com.lightphone.spotify.playback.lockscreen

import com.lightphone.spotify.playback.lockscreen.LockScreenOverlayPolicy.Action
import com.lightphone.spotify.playback.lockscreen.LockScreenOverlayPolicy.Inputs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule for when the lock-screen row is on screen.
 *
 * Pinned because the row is drawn in a window nothing else owns: a decision that says "show" when it
 * should not leaves controls sitting over whatever the user opened next, and one that never says
 * "hide" leaves them there for good.
 */
class LockScreenOverlayPolicyTest {

    private fun inputs(
        enabled: Boolean = true,
        canDrawOverlays: Boolean = true,
        hasTrack: Boolean = true,
        screenOn: Boolean = true,
        appForeground: Boolean = false,
        dismissedThisWake: Boolean = false,
    ) = Inputs(enabled, canDrawOverlays, hasTrack, screenOn, appForeground, dismissedThisWake)

    @Test
    fun `screen on with a track loaded and the app in the background shows the row`() {
        assertEquals(Action.Show, LockScreenOverlayPolicy.decide(inputs(), shown = false))
    }

    /** Every one of these is a reason on its own. */
    @Test
    fun `each blocker keeps the row off`() {
        val blocked = listOf(
            inputs(enabled = false),
            inputs(canDrawOverlays = false),
            inputs(hasTrack = false),
            inputs(screenOn = false),
            inputs(appForeground = true),
            inputs(dismissedThisWake = true),
        )
        for (i in blocked) {
            assertEquals(Action.Nothing, LockScreenOverlayPolicy.decide(i, shown = false))
            assertEquals(Action.Hide, LockScreenOverlayPolicy.decide(i, shown = true))
        }
    }

    /** `addView` twice throws and `removeView` on a detached view logs a leak, so neither repeats. */
    @Test
    fun `it never adds or removes twice`() {
        assertEquals(Action.Nothing, LockScreenOverlayPolicy.decide(inputs(), shown = true))
        assertEquals(
            Action.Nothing,
            LockScreenOverlayPolicy.decide(inputs(screenOn = false), shown = false),
        )
    }

    /** Paused counts as loaded: a play button is the whole point of the row at that moment. */
    @Test
    fun `a paused track still shows`() {
        assertEquals(Action.Show, LockScreenOverlayPolicy.decide(inputs(hasTrack = true), shown = false))
    }
}
