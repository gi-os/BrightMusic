package com.lightphone.spotify

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.lightphone.spotify.ui.light.ColorMode

/**
 * Marks the app visible to the Rust reconnect monitor and warms the librespot
 * session when the user opens the app (before playlist delta sync).
 *
 * Resolves the controller from [App] each time so logout → re-pick backend still
 * talks to the live instance (not a torn-down singleton).
 */
class AppForegroundObserver(
    private val app: App,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        // Re-lift colour first: if a cover was on screen when the user left, it should be
        // in colour again by the time the window is visible.
        ColorMode.onAppVisible(app)
        val controller = app.controller ?: return
        controller.setAppForeground(true)
        controller.warmSpclientSessionAsync()
    }

    override fun onStop(owner: LifecycleOwner) {
        // The rest of the phone must be black-and-white even if the player is still open
        // underneath us. Holders are kept, so coming back re-lifts.
        ColorMode.onAppHidden(app)
        app.controller?.setAppForeground(false)
    }
}
