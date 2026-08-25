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
     * Whether *we* are the reason the panel is in colour.
     *
     * Was a nullable saved mode, and that is what stranded the phone in black and white. The old
     * [lift] returned early when the daltonizer was already off — "already colour" — without
     * recording anything, so a later restore had nothing to put back. Worse, because the write
     * only happened on the transition to one holder, a restore that ran *while* holders were
     * still held (leaving the app with the player open does exactly that) could never be undone
     * by a new screen acquiring: holders went 3 → 4, never 0 → 1, so nothing re-lifted and the
     * covers stayed grey until the process died.
     *
     * The fix is to stop treating this as a set of transitions at all. [apply] states what the
     * panel should be right now and makes it so, and every entry point calls it.
     */
    private var holdingColour = false

    /** The daltonizer mode to put back. LightOS pins 0 (simulate monochromacy). */
    private var savedMode: Int = 0

    /**
     * How many screens want colour, not whether one does. Now Playing and a detail header
     * can be alive at once, and with a boolean whichever released first would drop colour
     * out from under the other.
     */
    private var holders = 0

    /** False while the app is in the background, where the rest of the phone must stay mono. */
    private var appVisible = true

    fun acquire(context: Context) {
        cancelPendingRestore()
        holders++
        apply(context)
    }

    fun release(context: Context) {
        if (holders > 0) holders--
        if (holders > 0) {
            apply(context)
            return
        }
        // Debounced: a lazy list recycling rows disposes the last cover a frame before the next
        // one composes, and without this the panel strobes once per fling.
        val app = context.applicationContext
        val r = Runnable {
            pendingRestore = null
            apply(app)
        }
        pendingRestore = r
        handler.postDelayed(r, RESTORE_DEBOUNCE_MS)
    }

    /** App left the foreground — the rest of the phone should be B&W again, immediately. */
    fun onAppHidden(context: Context) {
        cancelPendingRestore()
        appVisible = false
        apply(context)
    }

    /**
     * Back in the foreground — colour again if a cover is still on screen. Does not touch
     * [holders]: leaving the app is not the same as leaving the player.
     */
    fun onAppVisible(context: Context) {
        appVisible = true
        apply(context)
    }

    /**
     * Put the panel where it should be, from scratch, every time.
     *
     * Idempotent and stateless about how it got here, which is the whole point: any missed
     * transition self-corrects on the next call instead of stranding the phone in the wrong
     * mode until the process restarts.
     */
    private fun apply(context: Context) {
        val wantColour = holders > 0 && appVisible
        if (wantColour == holdingColour) return
        val resolver = context.contentResolver
        try {
            if (wantColour) {
                // Remember what to put back *before* turning it off. If it is already off, the
                // stored 0 is right anyway: LightOS pins monochromacy, so 0 is what "on" means
                // here, and assuming it is how a previously stranded phone recovers.
                savedMode = Settings.Secure.getInt(resolver, MODE, 0)
                Settings.Secure.putInt(resolver, ENABLED, 0)
            } else {
                Settings.Secure.putInt(resolver, MODE, savedMode)
                Settings.Secure.putInt(resolver, ENABLED, 1)
            }
            holdingColour = wantColour
        } catch (e: SecurityException) {
            // The one-time adb grant is missing (a reinstall drops it). Covers stay greyscale,
            // which is a degradation rather than a break — and saying so beats a silent no-op.
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; panel stays greyscale")
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

/**
 * Holds the phone in colour for the whole time this app is in front, in [ArtworkTreatment.COLOR].
 *
 * **Why the whole app rather than only the covers.** [ColorArtworkEffect] was the original answer
 * and it is still the honest one on its own terms — everything this app draws besides a cover is
 * greyscale by construction, so colouring the panel around a cover looks like colouring the cover.
 * What it is not is *stable*: leaving a cover writes greyscale back, and BrightControl's per-app
 * colour rule writes colour again the moment it sees the setting move, so the two of them take turns
 * and the panel flickers on every scroll. Two writers is the problem, not either write.
 *
 * So this app states one thing for as long as it is in front — colour — and BrightControl's preset
 * for this package states the same thing. Agreement is what makes a second writer harmless. Nothing
 * visible changes when there is no cover on screen, for the reason above.
 *
 * Still gated on the artwork treatment: somebody who picked DITHER or GREY asked for a mono phone,
 * and this is not the place to overrule that.
 */
@Composable
fun ColorAppEffect() {
    val context = LocalContext.current
    val wantsColor = ArtworkSettings.treatment == ArtworkTreatment.COLOR
    DisposableEffect(wantsColor) {
        if (wantsColor) ColorMode.acquire(context)
        onDispose { if (wantsColor) ColorMode.release(context) }
    }
}
