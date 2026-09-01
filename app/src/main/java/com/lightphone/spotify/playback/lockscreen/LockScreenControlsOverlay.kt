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
import com.lightphone.spotify.data.isEpisodeUri
import com.lightphone.spotify.playback.PlaybackUiState
import com.lightphone.spotify.radio.RadioBridge
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

    /** Kept because its glyph flips with play state. */
    private var playPause: ImageView? = null

    /**
     * The outer two, kept because on a podcast they are a 15-second jump rather than a track
     * skip — an episode is loaded on its own, so "next" has nowhere to go, while jumping back
     * over the sentence you missed is the thing you reach for constantly.
     */
    private var skipBack: ImageView? = null
    private var skipForward: ImageView? = null

    /** True while the loaded item is a podcast episode, which decides the outer two glyphs. */
    private var isEpisode: Boolean = false

    /**
     * The radio, when a station is playing.
     *
     * A stream has nothing to skip to and no Spotify track behind it, so the row becomes
     * play/pause with a save button where "next" would be — and the title line becomes whatever
     * the station says is on. Read from [RadioBridge] because the radio is owned by the
     * ViewModel, which this service cannot see.
     */
    private var radio: RadioBridge.Snapshot = RadioBridge.Snapshot()

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
        val remote = controller.connect.state.value
        val remotePlayback = controller.connect.remotePlayback.value
        val nowRadio = RadioBridge.state.value
        val radioChanged = nowRadio != radio
        radio = nowRadio
        // While a speaker owns playback the local state is a paused engine, so the row has to
        // read the remote mirror or it shows a play glyph over music that is already playing.
        // A live station counts as something to control even though the engine holds nothing.
        hasTrack = state.currentUri != null ||
            (remote.isRemote && remotePlayback?.uri != null) ||
            nowRadio.active
        val playing = if (nowRadio.active) nowRadio.isPlaying else controller.routedIsPlaying()
        val episode = state.currentUri.isEpisodeUri()
        val glyphChanged = playing != isPlaying || episode != isEpisode || radioChanged
        isPlaying = playing
        isEpisode = episode
        // Title only: the artist would need a second line, and the lock screen has a clock, a date and
        // a home circle on it already.
        val newTitle = if (nowRadio.active) {
            nowRadio.title.orEmpty()
        } else if (remote.isRemote) {
            remotePlayback?.title.orEmpty().ifBlank { state.title.orEmpty() }
        } else {
            state.title.orEmpty()
        }
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

    private fun inputs(): LockScreenOverlayPolicy.Inputs {
        val enabled = LockScreenOverlaySettings.enabled
        val canDraw = LockScreenOverlaySettings.canDrawOverlays(context)
        return LockScreenOverlayPolicy.Inputs(
            enabled = enabled,
            canDrawOverlays = canDraw,
            hasTrack = hasTrack,
            screenOn = screenOn,
            // [onLightOs] is a UsageStatsManager.queryEvents sweep, and [pollTopApp] lands here
            // every 700ms while the screen is on. When the row can never show anyway — setting
            // off, overlays not granted, nothing loaded — skip the query; the policy reads the
            // false the same way it reads "another app is in front".
            onLightOs = if (enabled && canDraw && hasTrack) onLightOs() else false,
            appForeground = AppVisibility.foreground,
            dismissedThisWake = dismissedThisWake,
        )
    }

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

    /**
     * When the press was ours, not the lock screen's.
     *
     * Belt to the flag change's braces: a `removeView` inside the same gesture as a tap eats the tap,
     * so any dismissal arriving within a few frames of a button going down is treated as part of that
     * press and ignored.
     */
    private var lastButtonTouchMs: Long = 0

    private fun dismiss() {
        if (System.currentTimeMillis() - lastButtonTouchMs < BUTTON_GESTURE_GRACE_MS) return
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
        // Routed, not local. With playback handed to a speaker these used to drive the local
        // engine, which is paused during a handoff — three buttons that did nothing.
        val back = glyph(iconPx, backGlyphRes()) {
            when {
                // Nothing to go back to in a live stream.
                radio.active -> Unit
                isEpisode -> controller.seekBy(-EPISODE_JUMP_MS)
                else -> controller.routedPrevious()
            }
        }
        val toggle = glyph(iconPx, playGlyphRes()) {
            if (radio.active) RadioBridge.playPause() else controller.routedPlayPause()
        }
        val ahead = glyph(iconPx, forwardGlyphRes()) {
            when {
                radio.active -> RadioBridge.toggleSaved()
                isEpisode -> controller.seekBy(EPISODE_JUMP_MS)
                else -> controller.routedNext()
            }
        }
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
        val params = overlayParams(rowHeightPx, watchOutside = true).apply {
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
        skipBack = back
        skipForward = ahead
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
        // No outside-touch flag on this one. Two windows both watching for touches outside
        // themselves means a press on the buttons is "outside" the title, so the title window
        // reported it, the row was dismissed mid-gesture, and the view was detached before the click
        // could be delivered — a tap that hid the controls and skipped no track. Only the controls
        // window watches; a press on the title is outside *it*, so tapping the title still dismisses.
        val params = overlayParams(titleHeightPx, watchOutside = false).apply {
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
    private fun overlayParams(heightPx: Int, watchOutside: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        heightPx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0,
        PixelFormat.TRANSLUCENT,
    )

    /**
     * Take the row away by sliding it off the bottom, not by deleting the window under it.
     *
     * The fields are cleared first, so as far as every other path is concerned the overlay is
     * already gone: a `show()` arriving mid-animation builds a fresh window rather than fighting
     * this one, and [LockScreenOverlayPolicy] keeps seeing a consistent `shown` answer.
     *
     * The view is removed in `withEndAction`, and again by the animation being cancelled if the
     * window goes early — `removeView` on a detached view only logs, while *not* removing one
     * leaks a window, so the safe direction is to try twice.
     */
    private fun hide() {
        val row = root
        val label = titleRoot
        root = null
        titleRoot = null
        playPause = null
        skipBack = null
        skipForward = null
        titleView = null
        slideOutAndRemove(label)
        slideOutAndRemove(row)
    }

    /**
     * Slide a window off the bottom, then remove it.
     *
     * Three things had to be true for this to be visible, and each one was its own bug.
     *
     * 1. **The window moves, not the view inside it.** A window is only as tall as the row it
     *    holds and clips its contents, so translating the child left view within its own bounds
     *    in the first few pixels — indistinguishable from the blink it was replacing.
     * 2. **Which way is "down" depends on the gravity.** `y` is an offset from whichever edge a
     *    window anchors to, and these two anchor to opposite ones: the controls row is
     *    TOP-anchored, the title BOTTOM-anchored, where a larger `y` is *higher up*. The title
     *    flew upward before vanishing.
     * 3. **Not ValueAnimator.** It multiplies its duration by
     *    `Settings.Global.animator_duration_scale`, and on a phone with animations turned off
     *    that is zero — every frame collapses into one and the row simply disappears, which is
     *    exactly what it did. This is a hand-driven tween on the main-thread handler, timed from
     *    the clock, so the system scale cannot flatten it.
     *
     * Cheap insurance either way: if `updateViewLayout` throws — the window can be taken away
     * underneath us when the screen sleeps — the tween removes the view and stops.
     */
    private fun slideOutAndRemove(view: android.view.View?) {
        if (view == null) return
        val wm = windowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams
        if (params == null || !view.isAttachedToWindow) {
            runCatching { wm.removeView(view) }
            return
        }
        val startY = params.y
        val bottomAnchored = (params.gravity and Gravity.BOTTOM) == Gravity.BOTTOM
        val direction = if (bottomAnchored) -1 else 1
        // Past its own height *and* the gap it was sitting in, so it clears the edge rather than
        // resting against it.
        val travel = (view.height.takeIf { it > 0 }
            ?: (context.resources.displayMetrics.heightPixels / 4)) + kotlin.math.abs(startY)
        val startedAt = android.os.SystemClock.uptimeMillis()

        val tick = object : Runnable {
            override fun run() {
                val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
                val f = (elapsed.toFloat() / EXIT_DURATION_MS).coerceIn(0f, 1f)
                // Smoothstep: eases out of rest and into the edge. A linear ramp starts and stops
                // abruptly, which is what read as a snap.
                val eased = f * f * (3f - 2f * f)
                params.y = startY + (travel * direction * eased).toInt()
                // Gone by two thirds of the way down: a row still fading after it has left the
                // screen is time spent watching nothing.
                view.alpha = (1f - eased / 0.66f).coerceIn(0f, 1f)
                val moved = runCatching { wm.updateViewLayout(view, params) }.isSuccess
                if (!moved || f >= 1f) {
                    runCatching { wm.removeView(view) }
                    return
                }
                handler.postDelayed(this, FRAME_MS)
            }
        }
        handler.post(tick)
    }


    private fun updateGlyph() {
        playPause?.setImageResource(playGlyphRes())
        skipBack?.setImageResource(backGlyphRes())
        skipForward?.setImageResource(forwardGlyphRes())
        // Invisible rather than GONE: the row is three even thirds by weight, and removing one
        // would slide play/pause off centre every time a station started.
        skipBack?.visibility = if (radio.active) android.view.View.INVISIBLE else android.view.View.VISIBLE
        // Nothing to save until a track has been identified; a star that does nothing during a
        // talk show invites a press and swallows it.
        skipForward?.visibility =
            if (radio.active && !radio.canSave) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    private fun backGlyphRes(): Int = when {
        // A stream has no previous track. The slot is drawn empty rather than removed, so the
        // play button stays in the middle of the screen where the thumb already expects it.
        radio.active -> LightR.drawable.ic_circle_white
        isEpisode -> LightR.drawable.ic_skip_backward_fifteen_white
        else -> LightR.drawable.ic_rewind_white
    }

    private fun forwardGlyphRes(): Int = when {
        // Filled once the track is in your library, hollow while it is not — the same pair the
        // player uses, so the state reads identically in both places.
        radio.active && radio.saved -> LightR.drawable.ic_star_white
        radio.active -> LightR.drawable.ic_star_outline_white
        isEpisode -> LightR.drawable.ic_skip_forward_fifteen_white
        else -> LightR.drawable.ic_fast_forward_white
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
            setOnTouchListener { _, _ ->
                lastButtonTouchMs = System.currentTimeMillis()
                // Never consumed: the click and long-press listeners below still have to run.
                false
            }
            setOnClickListener { onTap() }
            // A long press anywhere on the row puts it away, including on a button — which is why the
            // listener returns true: without it the press would also arrive as a tap and skip a track
            // on the way out.
            setOnLongClickListener {
                // Explicit, so it skips the grace window a stray dismissal is held back by.
                dismissedThisWake = true
                apply()
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

        /** Matches the SDK's 15-second glyphs, so the icon and the behaviour cannot drift apart. */
        const val EPISODE_JUMP_MS = 15_000L

        /** How long the row takes to slide off the bottom when it goes. */
        const val EXIT_DURATION_MS = 340L

        /** One frame at 60Hz. */
        const val FRAME_MS = 16L

        /** How long after a button is touched an outside-touch dismissal is assumed to be that touch. */
        const val BUTTON_GESTURE_GRACE_MS = 400L

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
