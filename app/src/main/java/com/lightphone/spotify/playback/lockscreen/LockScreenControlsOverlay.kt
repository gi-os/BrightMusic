package com.lightphone.spotify.playback.lockscreen

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.fonts.SystemFonts
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

    /** Kept because the track changes under it. */
    private var titleView: TextView? = null

    /** The title's window, separate from the controls' — see [showTitle]. */
    private var titleRoot: OverlayRoot? = null

    private val topApps = TopAppWatcher(context)
    private val handler = Handler(Looper.getMainLooper())

    private var screenOn: Boolean = isScreenOn()

    private var dismissedThisWake: Boolean = false
    private var hasTrack: Boolean = false
    private var isPlaying: Boolean = false
    private var title: String = ""

    /**
     * Re-asks which app is in front while the screen is on.
     *
     * There is no callback for this — [TopAppWatcher] reads usage stats, which is a poll by nature.
     * It runs only while the display is awake, so it costs nothing in the pocket, and it is what
     * takes the row away when the user leaves LightOS for another app.
     */
    private val pollTopApp = object : Runnable {
        override fun run() {
            if (!screenOn) return
            apply()
            handler.postDelayed(this, TOP_APP_POLL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // A wake is a fresh chance to show. Note this is also where a dismissal expires:
                    // hiding the row is for the rest of *this* look at the phone, not for good.
                    dismissedThisWake = false
                    handler.removeCallbacks(pollTopApp)
                    handler.post(pollTopApp)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    dismissedThisWake = false
                    handler.removeCallbacks(pollTopApp)
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
        if (screenOn) handler.post(pollTopApp)
        apply()
    }

    fun stop() {
        handler.removeCallbacks(pollTopApp)
        runCatching { context.unregisterReceiver(screenReceiver) }
        hide()
    }

    /** Feed playback state in. Cheap and idempotent — [apply] only touches the window on a change. */
    fun onState(state: PlaybackUiState) {
        hasTrack = state.currentUri != null
        val playing = state.isPlaying
        val glyphChanged = playing != isPlaying
        isPlaying = playing
        // Title only: the artist would need a second line, and the lock screen has a clock, a date and
        // a home circle on it already.
        val newTitle = state.title.orEmpty()
        val titleChanged = newTitle != title
        title = newTitle
        if (glyphChanged) updateGlyph()
        if (titleChanged) titleView?.text = title
        apply()
    }

    /** The app's own UI came to the front or went away. */
    fun onAppForegroundChanged() = apply()

    /**
     * A setting changed.
     *
     * Rebuilds rather than re-deciding, because one of the two settings is *which windows* exist and
     * [LockScreenOverlayPolicy] only answers whether anything should be shown at all.
     */
    fun onSettingChanged() {
        if (root != null) hide()
        apply()
    }

    private fun inputs() = LockScreenOverlayPolicy.Inputs(
        enabled = LockScreenOverlaySettings.enabled,
        canDrawOverlays = LockScreenOverlaySettings.canDrawOverlays(context),
        hasTrack = hasTrack,
        screenOn = screenOn,
        onLightOs = onLightOs(),
        appForeground = AppVisibility.foreground,
        dismissedThisWake = dismissedThisWake,
    )

    /**
     * Whether LightOS is in front. No guessing.
     *
     * v0.16 fell back to "the screen came on within the last minute" when the usage-stats appop was
     * missing, because v0.15's strict gate had made the row vanish entirely. That vanishing turned out
     * to be a different bug — the LightOS package was resolved with `resolveActivity`, which answers
     * the system `ResolverActivity` when no default launcher is set, so the comparison could never
     * hold — and with that fixed the fallback only buys the one behaviour that is not wanted: controls
     * over another app. The row is off unless the top package is known to be LightOS's.
     *
     * Ungranted, that is never known and the row never appears. Settings → Lock screen prints
     * `usage=off` and the adb line, so this fails loudly rather than silently.
     */
    private fun onLightOs(): Boolean = topApps.hasPermission() && topApps.isOnLightOs()

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
        val titleHeightPx = (unitPx * TITLE_ROW_GRID_UNITS).toInt()

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
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, rowHeightPx),
            )
        }
        val params = overlayParams(rowHeightPx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.heightPixels * ROW_CENTRE_FRACTION - rowHeightPx / 2f)
                .toInt()
                .coerceAtLeast(0)
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
        if (LockScreenOverlaySettings.titleEnabled) showTitle(wm, metrics, unitPx, titleHeightPx)
    }

    /**
     * The track title, in its own window along the bottom of the screen.
     *
     * A second window rather than one tall one, because a window swallows every touch inside it: a
     * single window reaching from the controls down to the bottom edge would make the whole lower fifth
     * of the lock screen dead to the touch, and would stop reporting the `ACTION_OUTSIDE` that a press
     * down there is supposed to produce. Two small windows keep the footprint to what is drawn. Either
     * one hearing an outside touch dismisses both.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showTitle(
        wm: WindowManager,
        metrics: android.util.DisplayMetrics,
        unitPx: Float,
        titleHeightPx: Int,
    ) {
        val label = TextView(context).apply {
            text = title
            typeface = akkurat()
            setTextColor(Color.WHITE)
            // The SDK's Detail size, which is 20 design px against a 600 px-tall reference panel —
            // so it is scaled by the real screen height the same way LightText scales it, rather than
            // being a dp guess that happens to look right on one device.
            setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                TITLE_DESIGN_PX * metrics.heightPixels / metrics.density / DESIGN_REFERENCE_PX,
            )
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            // A long track name must not push the row wider than the panel.
            setPadding((unitPx * TITLE_SIDE_PADDING_GRID_UNITS).toInt(), 0, (unitPx * TITLE_SIDE_PADDING_GRID_UNITS).toInt(), 0)
        }
        val container = OverlayRoot(context, onOutsideTouch = ::dismiss).apply {
            addView(
                label,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, titleHeightPx),
            )
        }
        val params = overlayParams(titleHeightPx).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (unitPx * TITLE_BOTTOM_MARGIN_GRID_UNITS).toInt()
        }
        val added = runCatching { wm.addView(container, params) }
        if (added.isFailure) {
            Log.w(TAG, "title not added: ${added.exceptionOrNull()?.message}")
            return
        }
        titleView = label
        titleRoot = container
    }

    /**
     * Flags shared by both windows.
     *
     * `FLAG_NOT_FOCUSABLE` is the one that matters: an overlay that takes focus takes key events with
     * it, and this one must never be able to hold a button the user needs.
     */
    private fun overlayParams(heightPx: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        heightPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    )

    private fun hide() {
        val row = root
        val label = titleRoot
        root = null
        titleRoot = null
        playPause = null
        titleView = null
        if (label != null) runCatching { windowManager?.removeView(label) }
        if (row != null) runCatching { windowManager?.removeView(row) }
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

    /**
     * The system Akkurat the SDK uses, or the platform default.
     *
     * `lightFontFamily` is a Compose `FontFamily` and cannot be handed to a `TextView`, but it looks
     * for the same thing this does: LightOS ships Akkurat as a system font, so it can be loaded
     * straight off disk. Falling back to the default is a slightly wrong typeface, not a crash.
     */
    private fun akkurat(): Typeface = runCatching {
        val file = SystemFonts.getAvailableFonts()
            .asSequence()
            .mapNotNull { it.file }
            .firstOrNull { it.name.startsWith("Akkurat", ignoreCase = true) }
            ?: return@runCatching Typeface.DEFAULT
        Typeface.Builder(file).build() ?: Typeface.DEFAULT
    }.getOrDefault(Typeface.DEFAULT)

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

        /**
         * Half the SDK's default icon size.
         *
         * The SDK's own default is 2 units and that is what LightOS's row uses, but LightOS draws that
         * row *instead of* the clock's lower half, with nothing else around it. Here the glyphs sit
         * under a title on a screen that already has a clock, a date and a home circle, and at 2 units
         * they read as a second interface rather than a caption.
         */
        const val ICON_GRID_UNITS = 1f

        /** Tap target height. Bigger than the glyph on purpose — a 1-unit target is a 15dp one. */
        const val ROW_GRID_UNITS = 2f

        /** One line of Detail text. */
        const val TITLE_ROW_GRID_UNITS = 1.6f

        /** How far the title sits off the bottom edge of the panel. */
        const val TITLE_BOTTOM_MARGIN_GRID_UNITS = 0.5f

        /** Keeps a long track name off the edges of the panel. */
        const val TITLE_SIDE_PADDING_GRID_UNITS = 2f

        /** The SDK's `detail` size, in design px. */
        const val TITLE_DESIGN_PX = 20f

        /** The height the SDK's type scale is written against. */
        const val DESIGN_REFERENCE_PX = 600f

        /**
         * Where the controls row sits, as a fraction of screen height.
         *
         * LightOS puts its own row at about 0.57 because it has a home circle to stay clear of below
         * it. Gio's lock screen has the circle switched off, so there is nothing down there to avoid
         * and the block sits lower, where a caption belongs. A fraction rather than a dp so it lands
         * in the same place if the panel metrics ever change.
         */
        const val ROW_CENTRE_FRACTION = 0.78f

        /** How often to re-ask which app is in front, while the screen is on. */
        const val TOP_APP_POLL_MS = 700L

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
