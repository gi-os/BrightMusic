package com.lightphone.spotify

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lightphone.spotify.hw.LightKey
import com.lightphone.spotify.hw.LightKeys
import com.lightphone.spotify.hw.LocalWheelBus
import com.lightphone.spotify.hw.WheelBus
import com.lightphone.spotify.ui.navigation.SpotifyApp

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
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
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
                SpotifyApp(
                    // Logout no longer returns to a picker; it just rebinds the ViewModel so
                    // the login screen starts from a clean state.
                    onReturnToPicker = {
                        viewModelStore.clear()
                        recreate()
                    },
                )
            }
        }
    }
}
