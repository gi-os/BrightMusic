package com.lightphone.spotify.playback

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CopyOnWriteArrayList

/** What the user picked. Not persisted — a sleep timer is a thing you set tonight. */
sealed class SleepChoice {
    data class Minutes(val minutes: Int) : SleepChoice()

    /** Stop when the current track — or episode — finishes. */
    data object EndOfItem : SleepChoice()

    companion object {
        /** The durations that suit going to bed. */
        val MINUTES = listOf(15, 30, 45, 60, 90)
    }
}

/**
 * Stop playing after a while, and fade rather than cut.
 *
 * ### Waking up to do it
 * The timer is an `AlarmManager` alarm, not a coroutine `delay` and not a `postDelayed`. The phone
 * is asleep for most of any sleep timer's life: the screen goes off, Doze starts, and the process is
 * still alive — a foreground service guarantees that much — but its threads are not running. Only an
 * `...AndAllowWhileIdle` alarm fires through that. `setExactAndAllowWhileIdle` where it is allowed,
 * which on this app it is: `USE_EXACT_ALARM` is declared, and a sleep timer is exactly the
 * clock-shaped use that permission exists for.
 *
 * ### One alarm, not two
 * The obvious shape is two alarms — one to start the fade, one to stop. It does not work. Both
 * flavours of allow-while-idle alarm are rationed to roughly one firing every nine to fifteen minutes
 * per app, so a second alarm twenty seconds behind the first is not late by twenty seconds, it is
 * late by a quarter of an hour. So there is one alarm, set for [SleepFade.FADE_MS] *before* the
 * deadline, and the fade and the stop both happen in the process it wakes — under a partial wake
 * lock, because the CPU would otherwise go straight back down between ticks.
 *
 * ### Fading
 * The last twenty seconds ramp to silence. A hard stop is a change in the room and wakes people up,
 * which defeats the entire feature. "End of track" does not fade: it was asked to let the track
 * finish, and a track that finishes has already ended on its own terms.
 *
 * ### Not outliving itself
 * The alarm is cancelled when the timer is cleared, when it fires, and when playback ends by any
 * other route. A sleep timer that survives its own expiry is an app that silently pauses your music
 * an hour later for no visible reason.
 */
object SleepTimer {
    private const val TAG = "SleepTimer"
    private const val REQUEST_CODE = 0x51EE
    private const val TICK_MS = 200L

    /** One per audio backend: librespot's sink, and the radio's `MediaPlayer`. */
    interface Output {
        /** Multiply this backend's output by [gain] (1.0 is untouched). */
        fun applyGain(gain: Float)

        /** Stop, as the timer expiring means stop. */
        fun stopPlayback()

        /** Whether this backend is the one making noise right now. */
        fun isPlaying(): Boolean
    }

    private val outputs = CopyOnWriteArrayList<Output>()

    fun registerOutput(output: Output) {
        if (!outputs.contains(output)) outputs.add(output)
    }

    data class State(
        val armed: Boolean = false,
        /** True for "end of track"/"end of episode", where there is no chosen duration. */
        val endOfItem: Boolean = false,
        /** The duration picked, for the screen to underline the row it came from. */
        val minutes: Int = 0,
        /** `SystemClock.elapsedRealtime()` at which playback stops. */
        val deadlineElapsedMs: Long = 0L,
        val fading: Boolean = false,
    )

    var state: State by mutableStateOf(State())
        private set

    /** Milliseconds until playback stops, or 0 when nothing is armed. */
    fun remainingMs(): Long =
        if (!state.armed) 0L else (state.deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val fadeTick = object : Runnable {
        override fun run() {
            val remaining = state.deadlineElapsedMs - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                expire()
                return
            }
            outputs.forEach { it.applyGain(SleepFade.gainAt(remaining)) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    // --- setting one --------------------------------------------------------

    fun start(context: Context, choice: SleepChoice, endOfItemDelayMs: Long = 0L) {
        val delay = when (choice) {
            is SleepChoice.Minutes -> choice.minutes * 60_000L
            SleepChoice.EndOfItem -> endOfItemDelayMs
        }
        if (delay <= 0L) return
        val endOfItem = choice is SleepChoice.EndOfItem
        arm(
            context = context,
            deadline = SystemClock.elapsedRealtime() + delay,
            endOfItem = endOfItem,
            minutes = (choice as? SleepChoice.Minutes)?.minutes ?: 0,
        )
    }

    /**
     * Add time to a running timer.
     *
     * An extended "end of track" becomes a clock timer: the track it was counting to is very likely
     * the one still playing, and "the end of this track, plus fifteen minutes" is not a thing anyone
     * means. The gain goes back to full in case the extension arrived mid-fade — which is when
     * people reach for it.
     */
    fun extend(context: Context, minutes: Int) {
        if (!state.armed) {
            start(context, SleepChoice.Minutes(minutes))
            return
        }
        val base = maxOf(state.deadlineElapsedMs, SystemClock.elapsedRealtime())
        stopFading()
        arm(
            context = context,
            deadline = base + minutes * 60_000L,
            endOfItem = false,
            minutes = 0,
        )
    }

    fun cancel(context: Context) {
        if (!state.armed) return
        stopFading()
        cancelAlarm(context)
        state = State()
    }

    /**
     * Playback ended some other way — the queue ran out, the user pressed stop, radio was left.
     * The alarm goes with it, so it cannot reach out of a forgotten evening and pause something
     * tomorrow.
     */
    fun onPlaybackStopped(context: Context) {
        if (!state.armed) return
        Log.i(TAG, "playback stopped before the timer; clearing it")
        cancel(context)
    }

    /**
     * Keep an "end of track" timer honest after a seek, a pause or a speed change.
     *
     * Called from the position updates the controller already receives. Re-arms only on real drift,
     * because rescheduling an exact alarm every second would be a wake-up per second.
     */
    fun refreshEndOfItem(context: Context, delayMs: Long?) {
        if (!state.armed || !state.endOfItem) return
        if (delayMs == null || delayMs <= 0L) return
        val wanted = SystemClock.elapsedRealtime() + delayMs
        if (Math.abs(wanted - state.deadlineElapsedMs) < 3_000L) return
        arm(context, wanted, endOfItem = true, minutes = 0)
    }

    private fun arm(context: Context, deadline: Long, endOfItem: Boolean, minutes: Int) {
        state = State(
            armed = true,
            endOfItem = endOfItem,
            minutes = minutes,
            deadlineElapsedMs = deadline,
            fading = false,
        )
        // "End of track" stops at the boundary and does not fade; everything else starts fading a
        // fade-length early, and that is what the single alarm is set for.
        val fireAt = if (endOfItem) deadline else deadline - SleepFade.FADE_MS
        scheduleAlarm(context, maxOf(fireAt, SystemClock.elapsedRealtime() + 1_000L))
    }

    private fun scheduleAlarm(context: Context, triggerAtElapsedMs: Long) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        runCatching {
            if (alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    pending,
                )
            } else {
                // Denied exact alarms: still fires in Doze, just not to the second. A sleep timer a
                // few minutes long in the tooth is worth more than no sleep timer.
                alarms.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMs,
                    pending,
                )
            }
        }.onFailure { Log.w(TAG, "could not schedule the sleep alarm", it) }
    }

    private fun cancelAlarm(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarms.cancel(pendingIntent(app)) }
    }

    private fun pendingIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app,
        REQUEST_CODE,
        Intent(app, SleepTimerReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // --- the alarm landing ---------------------------------------------------

    internal fun onAlarm(context: Context) {
        if (!state.armed) return
        if (outputs.none { it.isPlaying() }) {
            // Nothing to stop: the queue ended, or the process was rebuilt by the alarm itself.
            cancel(context)
            return
        }
        if (state.endOfItem || remainingMs() <= 0L) {
            expire()
            return
        }
        state = state.copy(fading = true)
        acquireWakeLock(context)
        handler.removeCallbacks(fadeTick)
        handler.post(fadeTick)
    }

    private fun expire() {
        handler.removeCallbacks(fadeTick)
        // Disarmed before anything is stopped, not after. Stopping a backend calls back in here —
        // radio's stop() reports itself as playback ending — and a re-entrant cancel would hand the
        // gain back at full for the 120ms the sink's own pause fade takes, which is a blip at the
        // exact moment the whole feature exists to avoid one.
        state = State()
        outputs.forEach {
            runCatching { it.stopPlayback() }
                .onFailure { e -> Log.w(TAG, "output would not stop", e) }
        }
        releaseWakeLock()
    }

    /**
     * Undo a fade in progress. The gain is handed back at full so the music is where the user left
     * it, which matters because cancelling mid-fade is the common case.
     */
    private fun stopFading() {
        handler.removeCallbacks(fadeTick)
        outputs.forEach { runCatching { it.applyGain(1f) } }
        releaseWakeLock()
    }

    private fun acquireWakeLock(context: Context) {
        if (wakeLock?.isHeld == true) return
        val power = context.applicationContext.getSystemService(PowerManager::class.java) ?: return
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightPhono:sleepfade").apply {
            setReferenceCounted(false)
            // Twice the fade, as a fuse. If anything below leaks, the lock still goes.
            runCatching { acquire(SleepFade.FADE_MS * 2) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }
}

/** Not exported: only this app's own alarm reaches it. */
class SleepTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Deliberately not `goAsync()`: the fade runs for twenty seconds, far past the ten a
        // broadcast is allowed to hold, so the work is handed to the process (kept awake by the
        // wake lock SleepTimer takes) and this returns straight away.
        SleepTimer.onAlarm(context.applicationContext)
    }
}
