package com.lightphone.spotify

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.lightphone.spotify.ui.components.AppLaunchFade
import com.lightphone.spotify.ui.navigation.SpotifyApp

/** One press of a volume key, as a percentage of the remote device's range. */
private const val VOLUME_STEP_PERCENT = 5

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /**
     * Every hardware key arrives here first — DecorView calls the window callback before it walks
     * the view hierarchy — which is what lets the wheel beat a focused WebView or text field.
     *
     * Both DOWN and UP are consumed: one notch is a complete DOWN+UP pair, and letting the UP
     * through means the search field can receive it as a keypress.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val app = application as App
        val vm = app.controller
        when (event.keyCode) {
            // D-pad up/down → scroll (wheel)
            KeyEvent.KEYCODE_DPAD_UP -> { wheel.send(1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { wheel.send(-1); return true }
            // D-pad left/right → switch tabs
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Handled by Compose focus system — let it through for tab nav
                return super.dispatchKeyEvent(event)
            }
            // D-pad center / Enter → activate focused item (let Compose handle it)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                return super.dispatchKeyEvent(event)

            // F1 (top-left) → Back
            KeyEvent.KEYCODE_F1 -> { onBackPressedDispatcher.onBackPressed(); return true }
            // F2 (top-right) → Play/Pause toggle
            KeyEvent.KEYCODE_F2 -> {
                vm?.let {
                    if (it.state.value.isPlaying) it.pause() else it.resume()
                }
                return true
            }
            // F3 (bottom-left) → Previous track
            KeyEvent.KEYCODE_F3 -> { vm?.previous(); return true }
            // F4 (bottom-right) → Next track
            KeyEvent.KEYCODE_F4 -> { vm?.next(); return true }

            // Number keys 1-9 → quick playlist access (future)
            // * → toggle shuffle
            // # → toggle repeat
            // 0 → queue
        }

        // Volume keys
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            val step = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_STEP_PERCENT
                KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_STEP_PERCENT
                else -> 0
            }
            if (step != 0) {
                val connect = (application as App).controller?.connect
                if (connect?.state?.value?.isRemote == true) {
                    if (event.action == KeyEvent.ACTION_DOWN && connect.nudgeVolume(step)) return true
                    if (event.action == KeyEvent.ACTION_UP) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Take the splash off screen the instant the first frame is ready, with no exit
        // animation of its own.
        //
        // This is why the launch fade could not be seen: the system splash plays its own
        // several-hundred-millisecond icon exit *over* the window, and [AppLaunchFade] was
        // ramping up underneath it. Removing the splash view outright hands the whole
        // transition to the app.
        installSplashScreen().setOnExitAnimationListener { provider -> provider.remove() }
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) {
                    android.util.Log.w(
                        "MainActivity",
                        "POST_NOTIFICATIONS denied; playback notification may be limited",
                    )
                }
            }.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            // Upstream phono gated this on a Spotify/TIDAL picker, which had to run before
            // AppViewModel existed because the controller was backend-specific. LightPhono
            // is Spotify-only, so App.onCreate pins the choice and this goes straight in.
            val app = application as App
            app.ensureController()
            app.controller?.offlineDownloads?.resumeDownloads(this)

            CompositionLocalProvider(LocalWheelBus provides wheel) {
                Box(Modifier.fillMaxSize()) {
                    AppLaunchFade {
                        SpotifyApp(
                            // Logout no longer returns to a picker; it just rebinds the ViewModel
                            // so the login screen starts from a clean state.
                            onReturnToPicker = {
                                viewModelStore.clear()
                                recreate()
                            },
                        )
                    }
                }
            }
        }
    }
}
