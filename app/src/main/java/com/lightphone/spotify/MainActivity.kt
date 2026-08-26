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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.gios.light.common.report.LightReport
import com.gios.light.common.report.ReportContext
import com.gios.light.common.report.ReportOverlay
import com.lightphone.spotify.ui.light.ColorAppEffect
import com.lightphone.spotify.ui.components.AppLaunchFade
import com.lightphone.spotify.ui.lightInset
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
        // While casting, the volume keys belong to the speaker.
        //
        // Without this they moved the *phone's* stream, which is silent during a handoff — the
        // keys looked broken while the only volume that mattered sat behind the in-app slider.
        // Falls through untouched when nothing is being driven (or when the remote has not
        // reported a volume, as some devices never do), so local playback is unaffected.
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            val step = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> VOLUME_STEP_PERCENT
                KeyEvent.KEYCODE_VOLUME_DOWN -> -VOLUME_STEP_PERCENT
                else -> 0
            }
            if (step != 0) {
                val connect = (application as App).controller?.connect
                if (connect?.state?.value?.isRemote == true) {
                    // Act on DOWN, swallow the matching UP so the system slider never appears.
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
        // Arms the crash handler as well as naming the app. Everything else about reporting —
        // the sensor, the crash-log offer, the screenshot, the queue, the sheet — belongs to
        // ReportOverlay further down.
        //
        // The accelerometer still only runs while BrightMusic is in front, which is a real limit
        // for a music player: the moment you most want to report something is a track that will
        // not start with the phone in a pocket. Playback failures are meant to reach Trouble
        // instead, which does not need the app on screen.
        LightReport.install(
            context = this,
            appName = "Phono",
            label = "phono",
            token = BuildConfig.REPORT_TOKEN,
        )

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
            // Colour for as long as the app is in front, not only around a cover. See
            // ColorAppEffect for why the narrower version could not hold.
            ColorAppEffect()
            // Keyed, not bare: this is a `startForegroundService`, and called from the composable
            // body it fired on every single recomposition of the root — hundreds of starts, each one
            // landing in the download service and each one previously spawning its own drain loop
            // that could tear the service down under a transfer already running. Once per controller
            // is what "resume on app open" ever meant.
            val downloads = app.controller?.offlineDownloads
            LaunchedEffect(downloads) { downloads?.resumeDownloads(this@MainActivity) }
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

                    // Bottom-start, clear of the transport controls on the right of the
                    // now-playing screen. The whole feature, in its own window, so it does not
                    // matter that the call sits inside this Box.
                    ReportOverlay(inset = lightInset())
                }
            }
        }
    }
}
