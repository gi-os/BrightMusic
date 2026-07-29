package com.lightphone.spotify

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lightphone.spotify.ui.navigation.SpotifyApp

class MainActivity : ComponentActivity() {

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
