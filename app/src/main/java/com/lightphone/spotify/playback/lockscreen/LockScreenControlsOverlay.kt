package com.lightphone.spotify.playback.lockscreen

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.lightphone.spotify.playback.PlaybackController
import com.lightphone.spotify.playback.PlaybackUiState
import com.thelightphone.sdk.ui.LightGrid
// The glyphs live in the light-ui module, and `android.nonTransitiveRClass` (AGP's default) keeps a
// library's resources out of the app's R class — so they are addressed through light-ui's own R.
import com.thelightphone.sdk.ui.R as LightR

/**
 * The playback row LightOS draws on its lock screen for its own player, drawn for this one.
 *
 * ### Why an overlay window and not something supported
 *
 * There is no supported hook. LightOS's lock screen is not the Android keyguard: it is the
 * `com.lightos` system app's own activity, force-started on `ACTION_SCREEN_OFF`. It renders its media
 * row from its own player, and the Light SDK has no media API at all — no `MediaSession` surface, no
 * now-playing hook, and the SDK emulator's `LightLockscreen` is a status row, a clock and a home
 * circle with no media row in it. This app's platform session is correct and visible
 * (`dumpsys media_session` lists it as the media button session, with a MediaStyle notification at
 * importance 3), and LightOS still does not draw it. So the row is drawn here instead, in a
 * `TYPE_APPLICATION_OVERLAY` window that sits above that activity — which works precisely *because*
 * the lock screen is an ordinary app window rather than a keyguard surface. On a phone with a real
 * device PIN the secure keyguard would cover this; the LPIII as configured has none.
 *
 * ### Deliberately plain Views
 *
 * A `ComposeView` outside an Activity needs a `ViewTreeLifecycleOwner`, a
 * `ViewTreeViewModelStoreOwner` and a `ViewTreeSavedStateRegistryOwner` set by hand or it throws at
 * attach. Three `ImageView`s need none of that, and the design language here is the drawables and the
 * grid, both of which a View can use: the glyphs are the SDK's own `ic_rewind` / `ic_pause` /
 * `ic_play` / `ic_fast_forward`, at the SDK's default icon size of two grid units, on the 27-unit
 * horizontal grid.
 *
 * ### Never trapping the user
 *
 * `FLAG_NOT_FOCUSABLE` is not optional: an overlay that takes focus takes key events with it, which is
 * how an ambient-display experiment in a sibling app once ate the button that would have dismissed it.
 * This window never has focus, is only as tall as the row, and passes every touch outside itself
 * through to the lock screen underneath — while still being *told* about those touches, which is what
 * makes the home circle work as a dismissal.
 */
class LockScreenControlsOverlay(
    private val context: Context,
    private val controller: PlaybackController,
) {

    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java)

    private var root: OverlayRoot? = null

    /** Kept because its glyph flips with play state; the other two never change. */
    private var playPause: ImageView? = null

    private var screenOn: Boolean = isScreenOn()
    private var dismissedThisWake: Boolean = false
    private var hasTrack: Boolean = false
    private var isPlaying: Boolean = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // A wake is a fresh chance to show. Note this is also where a dismissal expires:
                    // hiding the row is for the rest of *this* look at the phone, not for good.
                    dismissedThisWake = false
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    dismissedThisWake = false
                }
                else -> return
            }
            apply()
        }
    }

    fun start() {
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        apply()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(screenReceiver) }
        hide()
    }

    /** Feed playback state in. Cheap and idempotent — [apply] only touches the window on a change. */
    fun onState(state: PlaybackUiState) {
        hasTrack = state.currentUri != null
        val playing = state.isPlaying
        val glyphChanged = playing != isPlaying
        isPlaying = playing
        if (glyphChanged) updateGlyph()
        apply()
    }

    /** The app's own UI came to the front or went away. */
    fun onAppForegroundChanged() = apply()

    /** The setting changed. */
    fun onSettingChanged() = apply()

    private fun inputs() = LockScreenOverlayPolicy.Inputs(
        enabled = LockScreenOverlaySettings.enabled,
        canDrawOverlays = LockScreenOverlaySettings.canDrawOverlays(context),
        hasTrack = hasTrack,
        screenOn = screenOn,
        appForeground = AppVisibility.foreground,
        dismissedThisWake = dismissedThisWake,
    )

    private fun apply() {
        // WindowManager is main-thread only, and playback state arrives from a coroutine.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { apply() }
            return
        }
        when (LockScreenOverlayPolicy.decide(inputs(), shown = root != null)) {
            LockScreenOverlayPolicy.Action.Show -> show()
            LockScreenOverlayPolicy.Action.Hide -> hide()
            LockScreenOverlayPolicy.Action.Nothing -> Unit
        }
    }

    private fun dismiss() {
        dismissedThisWake = true
        apply()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        if (root != null) return
        val wm = windowManager ?: return
        val metrics = context.resources.displayMetrics
        val unitPx = metrics.widthPixels / LightGrid.WIDTH.toFloat()
        val iconPx = (unitPx * ICON_GRID_UNITS).toInt()
        val rowHeightPx = (unitPx * ROW_GRID_UNITS).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }
        val back = glyph(iconPx, LightR.drawable.ic_rewind_white) { controller.previous() }
        val toggle = glyph(iconPx, playGlyphRes()) {
            if (isPlaying) controller.pause() else controller.resume()
        }
        val ahead = glyph(iconPx, LightR.drawable.ic_fast_forward_white) { controller.next() }
        // Even thirds by weight rather than three fractional widths — a Row of `fillMaxWidth(1/3f)`
        // children compounds to a third, then two ninths, then four twenty-sevenths, and the row ends
        // up visibly left-of-centre. The same trap exists with LinearLayout widths.
        for (view in listOf(back, toggle, ahead)) {
            row.addView(
                view,
                LinearLayout.LayoutParams(0, rowHeightPx, 1f).apply { gravity = Gravity.CENTER },
            )
        }

        val container = OverlayRoot(context, onOutsideTouch = ::dismiss).apply {
            addView(
                row,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    rowHeightPx,
                ),
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            rowHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.heightPixels * ROW_CENTRE_FRACTION - rowHeightPx / 2f).toInt()
        }

        val added = runCatching { wm.addView(container, params) }
        if (added.isFailure) {
            // Revoked appop, or a manufacturer that refuses the type outright. Nothing to recover:
            // the app behaves as it did before the feature existed.
            Log.w(TAG, "overlay not added: ${added.exceptionOrNull()?.message}")
            return
        }
        playPause = toggle
        root = container
    }

    private fun hide() {
        val current = root ?: return
        root = null
        playPause = null
        runCatching { windowManager?.removeView(current) }
    }

    private fun updateGlyph() {
        playPause?.setImageResource(playGlyphRes())
    }

    private fun playGlyphRes(): Int =
        if (isPlaying) {
            LightR.drawable.ic_pause_white
        } else {
            LightR.drawable.ic_play_white
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun glyph(iconPx: Int, res: Int, onTap: () -> Unit): ImageView =
        ImageView(context).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // The drawables are already white-filled, but the lock screen is always dark and a tint
            // costs nothing — this way a black variant slipping in cannot make the row invisible.
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            // The tap target is the whole third of the row; the glyph inside it is two grid units.
            setPadding(0, 0, 0, 0)
            minimumWidth = iconPx
            minimumHeight = iconPx
            isClickable = true
            isLongClickable = true
            setOnClickListener { onTap() }
            // A long press anywhere on the row puts it away, including on a button — which is why the
            // listener returns true: without it the press would also arrive as a tap and skip a track
            // on the way out.
            setOnLongClickListener {
                dismiss()
                true
            }
        }

    private fun isScreenOn(): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    /**
     * Root view whose only job is to hear about touches that are not its own.
     *
     * `FLAG_WATCH_OUTSIDE_TOUCH` delivers `ACTION_OUTSIDE` for a press anywhere else on screen, with
     * the coordinates stripped — which is all that is needed. The event still reaches the window
     * underneath, so the home circle, the unlock swipe and LightOS's own controls keep working.
     */
    private class OverlayRoot(
        context: Context,
        private val onOutsideTouch: () -> Unit,
    ) : FrameLayout(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch()
                return true
            }
            return super.onTouchEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch()
                return true
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private companion object {
        const val TAG = "LockOverlay"

        /** The SDK's default icon size. */
        const val ICON_GRID_UNITS = 2f

        /** Tall enough to be a comfortable tap target without covering the clock or the home circle. */
        const val ROW_GRID_UNITS = 4f

        /**
         * Where LightOS puts the same row on its own lock screen — below the clock, above the circle.
         * A fraction rather than a dp so it lands in the same place if the panel metrics ever change.
         */
        const val ROW_CENTRE_FRACTION = 0.57f
    }
}

/**
 * Whether any of this app's own windows are in front.
 *
 * Written by the process lifecycle observer that already exists for the reconnect monitor. Kept here
 * rather than read from the controller's state because the controller's foreground flag is pushed
 * across the FFI and never read back on this side.
 */
object AppVisibility {
    @Volatile
    var foreground: Boolean = false
        private set

    var onChanged: (() -> Unit)? = null

    fun set(value: Boolean) {
        if (foreground == value) return
        foreground = value
        onChanged?.invoke()
    }
}
