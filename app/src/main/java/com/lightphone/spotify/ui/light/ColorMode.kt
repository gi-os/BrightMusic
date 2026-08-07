package com.lightphone.spotify.ui.light

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

/**
 * Temporarily lifts LightOS's forced greyscale so album art renders in real colour.
 *
 * The Light Phone III panel is a **full-colour AMOLED**. Its black-and-white look is
 * Android's accessibility colour-correction (the daltonizer) pinned to monochromacy — a
 * secure setting, and a SurfaceFlinger colour-matrix change, so flipping
 * `accessibility_display_daltonizer_enabled` off shows true colour instantly with no
 * restart.
 *
 * Writing it needs `WRITE_SECURE_SETTINGS`, which is `signature|privileged|development`
 * and so grantable over adb exactly once:
 *
 *     adb shell pm grant com.lightphone.spotify android.permission.WRITE_SECURE_SETTINGS
 *
 * Without the grant every call here no-ops (the SecurityException is swallowed) and covers
 * simply stay greyscale like the rest of the phone — which is why
 * [ArtworkTreatment.COLOR] is safe as the default.
 *
 * Ported from LightChat's `ColorMode`, which uses the same trick (originally vandamd's)
 * for its image viewer. Kept as a straight port on purpose: the reference-counting and
 * the foreground handling below are load-bearing and were arrived at the hard way.
 */
object ColorMode {
    private const val TAG = "ColorMode"
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"

    /**
     * How long a zero-holder state must last before greyscale actually comes back.
     *
     * Small covers in a lazy list hold colour now, and a lazy list recycles: during a
     * scroll the last visible cover can dispose a frame before the next one composes.
     * Without the debounce that gap is a full restore-then-lift round trip — the whole
     * screen strobing B&W for one frame per fling. 250ms is invisible next to the scroll
     * itself and cancels the moment anything re-acquires.
     */
    private const val RESTORE_DEBOUNCE_MS = 250L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingRestore: Runnable? = null

    private fun cancelPendingRestore() {
        pendingRestore?.let { handler.removeCallbacks(it) }
        pendingRestore = null
    }

    /**
     * The daltonizer mode to put back (LightOS pins 0 = simulate monochromacy). Non-null
     * exactly while we are holding the phone in colour.
     */
    private var savedMode: Int? = null

    /**
     * How many screens want colour, not whether one does. Now Playing and a detail header
     * can be alive at once, and with a boolean whichever released first would drop colour
     * out from under the other.
     */
    private var holders = 0

    fun acquire(context: Context) {
        cancelPendingRestore()
        holders++
        // If a debounced restore was pending, the panel is still in colour and [savedMode]
        // still holds what to put back — lift() sees "already colour" and no-ops. Correct.
        if (holders == 1) lift(context)
    }

    fun release(context: Context) {
        if (holders > 0) holders--
        if (holders == 0) {
            val app = context.applicationContext
            val r = Runnable {
                pendingRestore = null
                if (holders == 0) restore(app)
            }
            pendingRestore = r
            handler.postDelayed(r, RESTORE_DEBOUNCE_MS)
        }
    }

    /** App left the foreground — the rest of the phone should be B&W again, immediately. */
    fun onAppHidden(context: Context) {
        cancelPendingRestore()
        restore(context)
    }

    /** Back in the foreground — re-lift if a cover is still on screen. Does not touch
     *  [holders]: leaving the app is not the same as leaving the player. */
    fun onAppVisible(context: Context) {
        if (holders > 0) lift(context)
    }

    private fun lift(context: Context) {
        val resolver = context.contentResolver
        if (Settings.Secure.getInt(resolver, ENABLED, 0) != 1) return // already colour
        val mode = Settings.Secure.getInt(resolver, MODE, 0)
        try {
            Settings.Secure.putInt(resolver, ENABLED, 0)
            savedMode = mode
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; staying greyscale")
        }
    }

    private fun restore(context: Context) {
        val mode = savedMode ?: return
        try {
            Settings.Secure.putInt(context.contentResolver, MODE, mode)
            Settings.Secure.putInt(context.contentResolver, ENABLED, 1)
            savedMode = null
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS revoked mid-hold; can't restore greyscale")
        }
    }
}

/**
 * Holds the phone in colour for as long as the calling composable is on screen, but only
 * in [ArtworkTreatment.COLOR]. In the dither/greyscale modes the whole point is that the
 * panel stays mono, so nothing is touched.
 *
 * Note this is display-wide, not per-view: Android has no way to colourise one surface.
 * It reads as art-only anyway because LightPhono's palette is greyscale by construction,
 * so the only thing on screen with hues is the cover.
 */
@Composable
fun ColorArtworkEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    val wantsColor = enabled && ArtworkSettings.treatment == ArtworkTreatment.COLOR
    DisposableEffect(wantsColor) {
        if (wantsColor) ColorMode.acquire(context)
        onDispose { if (wantsColor) ColorMode.release(context) }
    }
}
