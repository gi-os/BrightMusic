package com.lightphone.spotify

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.light.common.hw.LightKey
import com.gios.light.common.hw.LightKeys
import com.gios.light.common.hw.LocalWheelBus
import com.gios.light.common.hw.WheelBus
import com.lightphone.spotify.report.CrashLog
import com.lightphone.spotify.report.Failure
import com.lightphone.spotify.report.ReportContext
import com.lightphone.spotify.report.Reports
import com.lightphone.spotify.report.Screenshot
import com.lightphone.spotify.report.ShakeDetector
import com.lightphone.spotify.report.Symptom
import com.lightphone.spotify.report.Trouble
import com.lightphone.spotify.ui.ReportChip
import com.lightphone.spotify.ui.ReportReason
import com.lightphone.spotify.ui.ReportSheet
import com.lightphone.spotify.ui.lightInset
import com.lightphone.spotify.ui.navigation.SpotifyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A report waiting to be offered. The screenshot is taken at the moment of the shake rather than
 * when the sheet asks for it — by then the sheet is what is on screen.
 */
private data class ReportRequest(
    val reason: ReportReason,
    val shot: Bitmap?,
    val failure: Failure? = null,
)

class MainActivity : ComponentActivity() {

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /** Raised by a shake, by a failure Phono noticed, or by a crash log from the last run. */
    private val reportRequest = MutableStateFlow<ReportRequest?>(null)

    /** True once the corner chip has been tapped. Ignoring the chip never gets here. */
    private val reportSheetOpen = MutableStateFlow(false)

    /** Null on a phone with no accelerometer, where the whole feature quietly does not exist. */
    private var shake: ShakeDetector? = null

    /** A shake, caught. Take the picture before the chip covers what looked wrong. */
    private fun onShaken() {
        if (reportRequest.value != null) return
        shake?.stop()
        Screenshot.capture(window) { bitmap ->
            reportRequest.value = ReportRequest(ReportReason.Shaken, bitmap)
        }
    }

    /**
     * The accelerometer runs only while Phono is the app you are looking at.
     *
     * Which is a real limit here and worth being honest about: Phono is a music player, so the
     * time you are most likely to want to report something — a track that will not start while
     * the phone is in a pocket — is exactly the time this is not listening. Playback failures
     * are meant to be caught by Trouble instead, which does not need the app in front.
     */
    override fun onResume() {
        super.onResume()
        if (reportRequest.value == null) shake?.start()
    }

    override fun onPause() {
        super.onPause()
        shake?.stop()
    }

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
        CrashLog.install(this)
        shake = ShakeDetector(this, ::onShaken).takeIf { it.available }
        if (savedInstanceState == null && CrashLog.read(this) != null) {
            reportRequest.value = ReportRequest(ReportReason.Crashed, null)
        }

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
            LaunchedEffect(Unit) { Reports.flush(this@MainActivity) }

            val reports = rememberCoroutineScope()
            val report by reportRequest.collectAsStateWithLifecycle()
            val sheetOpen by reportSheetOpen.collectAsStateWithLifecycle()

            val trouble by Trouble.latest.collectAsStateWithLifecycle()
            LaunchedEffect(trouble) {
                val failure = trouble ?: return@LaunchedEffect
                Trouble.clear()
                if (reportRequest.value != null) return@LaunchedEffect
                shake?.stop()
                Screenshot.capture(window) { bitmap ->
                    reportRequest.value = ReportRequest(ReportReason.Failed, bitmap, failure)
                }
            }

            CompositionLocalProvider(LocalWheelBus provides wheel) {
                Box(Modifier.fillMaxSize()) {
                    SpotifyApp(
                        // Logout no longer returns to a picker; it just rebinds the ViewModel so
                        // the login screen starts from a clean state.
                        onReturnToPicker = {
                            viewModelStore.clear()
                            recreate()
                        },
                    )

                    // Bottom-start, clear of the transport controls on the right of the
                    // now-playing screen.
                    report?.takeIf { !sheetOpen }?.let { pending ->
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = lightInset(), bottom = lightInset()),
                        ) {
                            ReportChip(
                                reason = pending.reason,
                                onOpen = { reportSheetOpen.value = true },
                                onExpire = {
                                    reportRequest.value = null
                                    shake?.start()
                                },
                            )
                        }
                    }
                }
            }

            report?.takeIf { sheetOpen }?.let { pending ->
                ReportSheet(
                    reason = pending.reason,
                    hasScreenshot = pending.shot != null,
                    failure = pending.failure?.what,
                    seedNote = pending.failure?.let { "Could not ${it.what}" }.orEmpty(),
                    onDismiss = {
                        if (pending.reason == ReportReason.Crashed) CrashLog.clear(this@MainActivity)
                        reportSheetOpen.value = false
                        reportRequest.value = null
                        shake?.start()
                    },
                    onSend = { symptom, note, includeScreenshot ->
                        reportSheetOpen.value = false
                        reportRequest.value = null
                        shake?.start()
                        reports.launch {
                            withContext(Dispatchers.IO) {
                                val crash = if (
                                    pending.reason == ReportReason.Crashed ||
                                    symptom == Symptom.Crashed
                                ) {
                                    CrashLog.read(this@MainActivity)
                                } else {
                                    null
                                }
                                Reports.enqueue(
                                    this@MainActivity,
                                    Reports.compose(
                                        context = this@MainActivity,
                                        symptom = symptom,
                                        note = note,
                                        screen = ReportContext.screen,
                                        crash = crash,
                                        shot = pending.shot
                                            ?.takeIf { includeScreenshot }
                                            ?.let { Screenshot.encode(it) },
                                        failure = pending.failure,
                                    ),
                                )
                                if (crash != null) CrashLog.clear(this@MainActivity)
                            }
                            Reports.flush(this@MainActivity)
                        }
                    },
                )
            }
        }
    }
}
