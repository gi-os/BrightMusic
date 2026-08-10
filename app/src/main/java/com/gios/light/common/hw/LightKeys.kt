package com.gios.light.common.hw

import android.view.KeyEvent
import android.webkit.WebView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Hardware key identities on the Light Phone III.
 *
 * On the Kyocera KY-42C these are stubs: the phone has no wheel, so the wheel entries are never
 * returned by [LightKeys.of] and the wheel dispatch in `MainActivity` falls straight through to
 * the D-pad/keypad path. The enum stays so the `when (LightKeys.of(event))` branches in the app
 * remain exhaustive and compile unchanged.
 */
enum class LightKey {
    Back,
    Up,
    Down,
    Left,
    Right,
    Center,
    WheelUp,
    WheelDown,
}

/**
 * Maps [KeyEvent]s to [LightKey]s.
 *
 * On the Light Phone III this recognises the scroll-wheel keycodes. The Kyocera has no wheel, so
 * every key resolves to `null` here — `MainActivity.dispatchKeyEvent` then falls through to its
 * D-pad / volume handling, which is exactly what we want.
 */
object LightKeys {
    fun of(event: KeyEvent): LightKey? = null
}

/**
 * Bus that carries wheel notches to whichever screen is showing.
 *
 * The Light Phone III posts scroll-wheel events through this. The Kyocera has no wheel — the
 * D-pad drives scrolling directly via key events — so [send] is a no-op and nothing is ever
 * delivered. The class is kept as a concrete type so `MainActivity` can construct one and wire it
 * into [LocalWheelBus] unchanged.
 */
open class WheelBus {
    /** A wheel notch: +1 for up, -1 for down. No-op on the Kyocera. */
    open fun send(notch: Int) { /* no-op */ }
}

/**
 * CompositionLocal carrying the active [WheelBus].
 *
 * The default is a no-op [WheelBus]; nothing reads it on the Kyocera because every [WheelScroll]
 * and [WheelGate] below is a no-op.
 */
val LocalWheelBus = staticCompositionLocalOf<WheelBus> { WheelBus() }

/**
 * Gates wheel delivery to a subtree.
 *
 * On the Light Phone III this is what stops an overlay and the screen behind it both answering the
 * same notch. On the Kyocera there is no wheel to gate, so it just renders [content].
 *
 * @param active ignored — kept for source compatibility with the Light Phone III call sites.
 */
@Composable
fun WheelGate(active: Boolean, content: @Composable () -> Unit) {
    content()
}

/**
 * Drives a scroll surface from the hardware wheel.
 *
 * On the Light Phone III each wheel notch scrolls the bound surface. The Kyocera scrolls via
 * D-pad key events that Compose dispatches through the normal focus path, so every overload here
 * is a no-op — the surface keeps working, it just isn't wheel-driven.
 *
 * The overloads mirror the original library: one for [ScrollState], one for [LazyListState], and
 * one for a [WebView] (the OAuth consent page). [active] is accepted for source compatibility and
 * ignored.
 */
@Composable
fun WheelScroll(state: ScrollState, active: Boolean = true) { /* no-op */ }

@Composable
fun WheelScroll(state: LazyListState, active: Boolean = true) { /* no-op */ }

@Composable
fun WheelScroll(webView: WebView?, active: Boolean = true) { /* no-op */ }
