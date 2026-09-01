package com.lightphone.spotify.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioDeviceInfo
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.lightphone.spotify.BuildConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * Native Android PCM output for librespot playback (Phase C).
 *
 * Rust drain thread calls [writePcmDirect] / [writePcmDirectBytes] via JNI.
 * Routing, [AudioAttributes], and recreate policy live here — not in cpal/AAudio.
 */
object PhonoAudioTrackSink {
    private const val TAG = "PhonoAudioTrack"
    private const val DEFAULT_SAMPLE_RATE = 44100
    private const val DEFAULT_CHANNELS = 2
    private const val FRAME_BYTES_STEREO_S16 = 4
    private const val MIN_RECREATE_INTERVAL_MS = 2_000L
    private const val STALL_RECREATE_MS = 200L
    private const val LOG_INTERVAL_MS = 5_000L
    private const val STALL_POLL_MS = 100L
    private const val DIRECT_BUFFER_BYTES = 8192

    /** The range the platform resampler is actually good over; see [setPlaybackSpeed]. */
    private const val MIN_SPEED = 0.5f
    private const val MAX_SPEED = 2.0f

    /**
     * Pause/resume fade. Short enough that the button still feels instant — anything longer reads as
     * the app being slow rather than the music easing off — and long enough to cover the discontinuity
     * that makes an abrupt pause click.
     */
    private const val FADE_MS = 120L
    private const val FADE_STEPS = 12
    private const val FADE_STEP_MS = FADE_MS / FADE_STEPS

    private val lock = ReentrantLock()
    private var track: AudioTrack? = null
    private var sampleRate = DEFAULT_SAMPLE_RATE
    private var channels = DEFAULT_CHANNELS
    private var bytesPerFrame = FRAME_BYTES_STEREO_S16
    private var lastVolume = 1.0f
    private var transportPaused = false
    /**
     * Output the user picked in the Bluetooth screen, or null to let Android route.
     *
     * Held here rather than applied once because the track is rebuilt on route changes, stalls and
     * dead objects — a preference set on the old track would be silently lost by the next recreate.
     */
    private var preferredOutput: AudioDeviceInfo? = null

    /**
     * Playback rate, held here for the same reason as [preferredOutput]: the track is rebuilt on
     * route changes, stalls and dead objects, and a rate set on the old track would be silently lost
     * by the next recreate — the episode would quietly drop back to 1x mid-listen.
     */
    private var playbackSpeed = 1.0f
    private var directWriteBuffer: ByteBuffer? = null
    private var writePending = ByteArray(0)

    private val positionTracker = PhonoAudioPositionTracker()
    private val coordinator = PhonoAudioSinkCoordinator()

    private val routingEventCount = AtomicInteger(0)
    private val deadObjectCount = AtomicInteger(0)
    private val writeErrorCount = AtomicInteger(0)
    private val recreateCount = AtomicInteger(0)
    private val drainPartialWrites = AtomicInteger(0)
    private val routingIgnoredCount = AtomicInteger(0)

    private var routeThread: HandlerThread? = null
    private var routeHandler: Handler? = null
    private val stallWatchRunnable = object : Runnable {
        override fun run() {
            val handler = routeHandler ?: return
            val keepWatching = lock.withLock {
                val t = track ?: return@withLock false
                if (transportPaused || t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    positionTracker.clearStall()
                    return@withLock false
                }
                val stalledMs = positionTracker.stalledMs(t, sampleRate, bytesPerFrame)
                if (stalledMs >= STALL_RECREATE_MS) {
                    Log.w(TAG, "playhead stalled ${stalledMs}ms — scheduling recreate")
                    coordinatorRecreate("stalled")
                }
                true
            }
            if (keepWatching) {
                handler.postDelayed(this, STALL_POLL_MS)
            }
        }
    }

    /** Exposed for [NativeInit.registerAudioSink] direct-buffer registration. */
    @JvmStatic
    fun prepareDirectBuffer(size: Int): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(max(size, DIRECT_BUFFER_BYTES))
            .order(ByteOrder.LITTLE_ENDIAN)
        directWriteBuffer = buf
        return buf
    }

    @JvmStatic
    fun ensureInitialized() {
        lock.withLock {
            if (routeThread == null) {
                routeThread = HandlerThread("PhonoAudioRoute").apply { start() }
                routeHandler = Handler(routeThread!!.looper)
            }
        }
    }

    private fun cancelStallWatchCallbacks() {
        routeHandler?.removeCallbacks(stallWatchRunnable)
    }

    private fun startStallWatch() {
        cancelStallWatchCallbacks()
        routeHandler?.postDelayed(stallWatchRunnable, STALL_POLL_MS)
    }

    private fun stopStallWatch() {
        cancelStallWatchCallbacks()
        lock.withLock { positionTracker.clearStall() }
    }

    @JvmStatic
    fun start(sampleRate: Int, channels: Int): Boolean {
        lock.withLock {
            this.sampleRate = sampleRate
            this.channels = channels
            bytesPerFrame = channels * 2
            transportPaused = false
            writePending = ByteArray(0)
            positionTracker.reset()
            releaseTrackLocked()
            return createAndPlayLocked()
        }
    }

    @JvmStatic
    fun writePcmDirect(buffer: ByteBuffer, size: Int): Int {
        lock.withLock {
            val t = track ?: return AudioTrack.ERROR_INVALID_OPERATION
            if (!buffer.isDirect) return AudioTrack.ERROR_BAD_VALUE
            return writeNonBlockingLocked(t, buffer, size)
        }
    }

    @JvmStatic
    fun writePcmDirectBytes(data: ByteArray, offset: Int, length: Int): Int {
        lock.withLock {
            val t = track ?: return AudioTrack.ERROR_INVALID_OPERATION
            if (length <= 0) return 0
            // Drain thread is dedicated — blocking write is correct here (player thread never blocks).
            val written = t.write(data, offset, length, AudioTrack.WRITE_BLOCKING)
            return handleWriteResultLocked(written, length)
        }
    }

    /**
     * Drop the PCM already queued for output, on a seek or a user-initiated load.
     *
     * `AudioTrack.flush()` is documented as taking effect only when the track is stopped or paused —
     * the native call returns early while it is ACTIVE. Called mid-playback, as this always is, it
     * therefore did nothing at all: the stale audio stayed queued, and the playhead kept counting
     * while `resetWrittenFrames` had just zeroed the written total. The
     * pending-latency estimate built from those two then read as an unsigned wrap — about 27 hours —
     * and `PlaybackController.audiblePositionMs` subtracts it from every reported position, so the
     * progress bar clamped to zero for the rest of the track and only came back on a pause/play,
     * which stops and restarts the track and resets both counters.
     *
     * So pause across the flush, and re-anchor the written total on the playhead afterwards rather
     * than assuming the flush zeroed it.
     */
    @JvmStatic
    fun flush() {
        lock.withLock {
            writePending = ByteArray(0)
            val t = track
            if (t == null) {
                positionTracker.resetWrittenFrames()
                return@withLock
            }
            val wasPlaying = t.playState == AudioTrack.PLAYSTATE_PLAYING
            runCatching {
                if (wasPlaying) t.pause()
                t.flush()
            }
            positionTracker.rebaseToPlayhead(t)
            if (wasPlaying && !transportPaused) {
                runCatching { t.play() }
            }
        }
    }

    /**
     * Transport pause — keep ring + track state; no flush.
     *
     * Ramps the gain to silence first so pausing sounds like the music stopping rather than the wire
     * being cut. [AudioTrack.setVolume] is applied by the mixer to audio already sitting in the
     * buffer, so the ramp is genuinely heard; simply calling `pause()` truncates mid-waveform and
     * clicks.
     *
     * Called from the Rust player thread via JNI, and blocks it for [FADE_MS]. That is deliberate: the
     * fade has to finish before the track is paused, or it is inaudible. The gain is left at zero and
     * restored by [resumeOutput], with [createAndPlayLocked] resetting it as a backstop for any path
     * that rebuilds the track while paused.
     */
    @JvmStatic
    fun pauseOutput() {
        rampGain(to = 0f)
        lock.withLock {
            transportPaused = true
            runCatching {
                track?.takeIf { it.playState == AudioTrack.PLAYSTATE_PLAYING }?.pause()
            }
        }
        stopStallWatch()
    }

    @JvmStatic
    fun resumeOutput() {
        lock.withLock {
            transportPaused = false
            // Start silent so the ramp has somewhere to come from — going straight to full volume
            // reintroduces exactly the click the fade-out avoids.
            runCatching { track?.setVolume(0f) }
            runCatching {
                track?.takeIf { it.playState != AudioTrack.PLAYSTATE_PLAYING }?.play()
            }
        }
        rampGain(to = lastVolume)
        if (track != null) {
            startStallWatch()
        }
    }

    /**
     * Walk the track's gain to [to] over [FADE_MS] in [FADE_STEPS] steps.
     *
     * Reads the track under [lock] but sleeps outside it: holding the lock across the ramp would block
     * the drain thread's writes for the whole fade, and on resume that would starve the buffer at the
     * exact moment it needs filling.
     *
     * Deliberately does not touch [lastVolume] — that is the level the user and normalization asked
     * for, and it is the ramp's destination, not its state.
     */
    private fun rampGain(to: Float) {
        val t = lock.withLock { track } ?: return
        val target = to.coerceIn(0f, 1f)
        val from = if (target > 0f) 0f else lastVolume
        if (from == target) {
            runCatching { t.setVolume(target) }
            return
        }
        for (step in 1..FADE_STEPS) {
            val gain = from + (target - from) * (step / FADE_STEPS.toFloat())
            if (!runCatching { t.setVolume(gain.coerceIn(0f, 1f)) }.isSuccess) return
            try {
                Thread.sleep(FADE_STEP_MS)
            } catch (e: InterruptedException) {
                // Someone wants this thread back. Land on the target rather than stranding the gain
                // part-way, which would leave playback quiet or loud for good.
                Thread.currentThread().interrupt()
                runCatching { t.setVolume(target) }
                return
            }
        }
    }

    @JvmStatic
    fun stop() {
        stopStallWatch()
        lock.withLock {
            transportPaused = false
            writePending = ByteArray(0)
            track?.let { t ->
                runCatching {
                    if (t.playState != AudioTrack.PLAYSTATE_STOPPED) {
                        t.stop()
                    }
                    t.flush()
                }
            }
            positionTracker.reset()
        }
    }

    @JvmStatic
    fun release() {
        lock.withLock { releaseTrackLocked() }
    }

    @JvmStatic
    fun requestRecreate() {
        routeHandler?.post { coordinatorRecreate("dead_object") }
    }

    @JvmStatic
    fun recreate(): Boolean {
        lock.withLock {
            recreateCount.incrementAndGet()
            val wasPlaying = track?.playState == AudioTrack.PLAYSTATE_PLAYING && !transportPaused
            releaseTrackLocked()
            writePending = ByteArray(0)
            positionTracker.reset()
            val ok = createAndPlayLocked()
            return ok && (wasPlaying || !transportPaused)
        }
    }

    /**
     * Route this app's audio to [device], or pass null to hand routing back to Android.
     *
     * `AudioTrack.setPreferredDevice` is the only public way an app can choose its own output — there
     * is no API to *connect* a Bluetooth device, only to prefer one that is already connected. So the
     * Bluetooth screen can switch between connected outputs, which is what a music player actually
     * wants, but cannot bring a paired-and-idle pair of headphones online.
     *
     * Returns false when the platform refuses the device, which it does if the device has gone away
     * between the list being built and the tap landing.
     */
    /**
     * Play at [speed] times normal, pitch preserved.
     *
     * `AudioTrack.setPlaybackParams` runs the platform's sonic time-stretcher, so this is speech at a
     * higher rate rather than speech at a higher pitch. It is applied to the live track and
     * remembered for the next one.
     *
     * Position bookkeeping is unaffected, which is worth stating because it looks like it should be:
     * `getPlaybackHeadPosition()` counts *source* frames consumed from the buffer, not output frames
     * produced, so the pending-latency estimate in [PhonoAudioPositionTracker] stays in the same
     * units as `totalFramesWritten` at any rate. The one thing that does change is how long the
     * buffered audio takes to be heard in wall-clock terms, and nothing reads it that way.
     *
     * Returns false if the platform refuses the rate, which it does for values outside what the
     * resampler supports — the caller keeps the old speed rather than believing a change that did
     * not happen.
     */
    @JvmStatic
    fun setPlaybackSpeed(speed: Float): Boolean {
        val wanted = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        return lock.withLock {
            playbackSpeed = wanted
            val t = track ?: return@withLock true // applied on next create
            applySpeedLocked(t, wanted)
        }
    }

    @JvmStatic
    fun getPlaybackSpeed(): Float = lock.withLock { playbackSpeed }

    /**
     * Push the rate onto a track.
     *
     * `setPlaybackParams` on a *stopped* track throws `IllegalStateException`, and on a rate the
     * device cannot do it throws `IllegalArgumentException`. Both are recoverable — the track keeps
     * playing at whatever rate it had — so neither is allowed to reach the caller, but a failure is
     * reported so the UI does not show a speed that is not being used.
     *
     * Setting only the speed and leaving pitch alone is what keeps the voice sounding right;
     * `PlaybackParams` defaults the fields it is not told about, so the pitch is never sent.
     */
    private fun applySpeedLocked(t: AudioTrack, speed: Float): Boolean = runCatching {
        val params = (runCatching { t.playbackParams }.getOrNull() ?: PlaybackParams())
            .setSpeed(speed)
        t.playbackParams = params
        true
    }.getOrElse { e ->
        Log.w(TAG, "setPlaybackParams(speed=$speed) refused", e)
        false
    }

    fun setPreferredOutput(device: AudioDeviceInfo?): Boolean {
        preferredOutput = device
        val t = lock.withLock { track } ?: return true // applied on next create
        return runCatching { t.setPreferredDevice(device) }.getOrDefault(false)
    }

    /** The output audio is actually coming out of right now, as opposed to the one requested. */
    fun currentRoutedDeviceId(): Int? = lock.withLock { track?.routedDevice?.id }

    @JvmStatic
    fun setVolume(volume: Float): Boolean {
        lastVolume = volume.coerceIn(0f, 1f)
        // Under the lock like every other `track` access: unguarded, this races the recreate
        // path and can call into an AudioTrack that is mid-release.
        lock.withLock { track?.setVolume(lastVolume) }
        return true
    }

    @JvmStatic
    fun getRoutingEventCount(): Int = routingEventCount.get()

    @JvmStatic
    fun getDeadObjectCount(): Int = deadObjectCount.get()

    @JvmStatic
    fun getWriteErrorCount(): Int = writeErrorCount.get()

    @JvmStatic
    fun getRecreateCount(): Int = recreateCount.get()

    @JvmStatic
    fun getDrainPartialWrites(): Int = drainPartialWrites.get()

    @JvmStatic
    fun getPendingOutputMs(): Int = lock.withLock {
        track?.let { positionTracker.pendingMs(it, sampleRate, bytesPerFrame) } ?: 0
    }

    /** Kotlin-side ring metric stub — Rust ring occupancy is authoritative in debug metrics. */
    @JvmStatic
    fun getRingOccupancyMs(): Int = 0

    /** Audible position adjustment for MediaSession (DelayMs). */
    @JvmStatic
    fun getOutputDelayMs(): Int = getPendingOutputMs()

    private fun createAndPlayLocked(): Boolean {
        ensureInitialized()
        val channelMask = if (channels == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        val halfSecondBytes = sampleRate * bytesPerFrame / 2
        var bufferSize = max(minBuf * 4, halfSecondBytes)
        val minLatencyBytes = sampleRate * bytesPerFrame / 4
        val maxLatencyBytes = sampleRate * bytesPerFrame * 3 / 4
        bufferSize = bufferSize.coerceIn(minLatencyBytes, maxLatencyBytes)

        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val newTrack = buildTrackWithRetry(attributes, format, bufferSize, minBuf * 4)
                ?: return false

            positionTracker.onTrackCreated(newTrack)
            newTrack.addOnRoutingChangedListener({ routedTrack ->
                routingEventCount.incrementAndGet()
                val deviceId = routedTrack.routedDevice?.id ?: Int.MIN_VALUE
                positionTracker.onRoutingChanged(deviceId)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "routing event deviceId=$deviceId (capabilities-first, no recreate)")
                }
            }, routeHandler)

            newTrack.setVolume(lastVolume)
            // Re-apply across recreates; see [preferredOutput] and [playbackSpeed].
            preferredOutput?.let { runCatching { newTrack.setPreferredDevice(it) } }
            if (playbackSpeed != 1.0f) applySpeedLocked(newTrack, playbackSpeed)
            if (!transportPaused) {
                newTrack.play()
                startStallWatch()
            }
            track = newTrack
            Log.i(
                TAG,
                "AudioTrack started rate=$sampleRate ch=$channels buf=$bufferSize " +
                    "minBuf=$minBuf routedDevice=${newTrack.routedDevice?.id}",
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack create failed", e)
            false
        }
    }

    private fun buildTrackWithRetry(
        attributes: AudioAttributes,
        format: AudioFormat,
        bufferSize: Int,
        retrySize: Int,
    ): AudioTrack? {
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack build failed buf=$bufferSize, retrying", e)
            try {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(retrySize)
                    .build()
            } catch (e2: Exception) {
                Log.e(TAG, "AudioTrack retry build failed", e2)
                null
            }
        }
    }

    private fun writeNonBlockingLocked(t: AudioTrack, buffer: ByteBuffer, size: Int): Int {
        if (writePending.isNotEmpty()) {
            val pendingWritten = t.write(writePending, 0, writePending.size, AudioTrack.WRITE_BLOCKING)
            val handled = handleWriteResultLocked(pendingWritten, writePending.size)
            if (handled <= 0) return handled
            if (handled < writePending.size) {
                drainPartialWrites.incrementAndGet()
                writePending = writePending.copyOfRange(handled, writePending.size)
                return handled
            }
            writePending = ByteArray(0)
        }

        val dup = buffer.duplicate()
        dup.position(buffer.position())
        dup.limit(buffer.position() + size)
        val written = t.write(dup, size, AudioTrack.WRITE_BLOCKING)
        val handled = handleWriteResultLocked(written, size)
        if (handled <= 0) return handled
        if (handled < size) {
            drainPartialWrites.incrementAndGet()
            val slice = ByteArray(size - handled)
            dup.position(buffer.position() + handled)
            dup.get(slice)
            writePending = slice
        }
        return handled
    }

    private fun coordinatorRecreate(reason: String) {
        if (!coordinator.tryBeginRecreate()) return
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - coordinator.lastRecreateAtMs < MIN_RECREATE_INTERVAL_MS) {
                routingIgnoredCount.incrementAndGet()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "recreate debounced reason=$reason")
                }
                return
            }
            lock.withLock {
                if (track == null) return@withLock
                coordinator.lastRecreateAtMs = now
                Log.i(TAG, "Recreating AudioTrack reason=$reason")
                recreateCount.incrementAndGet()
                val wasPlaying = track?.playState == AudioTrack.PLAYSTATE_PLAYING && !transportPaused
                releaseTrackLocked()
                writePending = ByteArray(0)
                positionTracker.reset()
                if (wasPlaying) {
                    createAndPlayLocked()
                } else {
                    stopStallWatch()
                }
            }
        } finally {
            coordinator.endRecreate()
        }
    }

    private fun handleWriteResultLocked(written: Int, attempted: Int): Int {
        if (written > 0) {
            val frames = written / bytesPerFrame
            positionTracker.onFramesWritten(frames.toLong())
            positionTracker.maybeLogStats(track, sampleRate, bytesPerFrame)
            return written
        }
        if (written == 0) {
            // WRITE_NON_BLOCKING backpressure — not an error.
            return 0
        }
        writeErrorCount.incrementAndGet()
        when (written) {
            AudioTrack.ERROR_DEAD_OBJECT -> {
                deadObjectCount.incrementAndGet()
                Log.w(TAG, "ERROR_DEAD_OBJECT — scheduling track recreate")
                routeHandler?.post { coordinatorRecreate("dead_object") }
            }
            AudioTrack.ERROR_INVALID_OPERATION -> {
                Log.w(TAG, "ERROR_INVALID_OPERATION on write — retry play()")
                runCatching {
                    track?.takeIf { !transportPaused }?.play()
                }
            }
            else -> Log.w(TAG, "AudioTrack write error: $written attempted=$attempted")
        }
        return written
    }

    private fun releaseTrackLocked() {
        cancelStallWatchCallbacks()
        positionTracker.clearStall()
        track?.let { t ->
            runCatching {
                if (t.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    t.stop()
                }
                t.flush()
                t.release()
            }.onFailure { e -> Log.w(TAG, "releaseTrack error", e) }
        }
        track = null
        positionTracker.reset()
    }
}

/** ExoPlayer-style playhead smoothing and pending latency estimate. */
private class PhonoAudioPositionTracker {
    private companion object {
        const val LOG_INTERVAL_MS = 5_000L
    }

    private var totalFramesWritten = 0L
    private var lastRoutedDeviceId = Int.MIN_VALUE
    private var lastLogAtMs = 0L
    private var writeCallCount = 0L
    private var lastPlayedFrames = 0L
    private var lastPlayedAtMs = 0L
    private var stallStartedAtMs = 0L

    fun reset() {
        totalFramesWritten = 0L
        lastPlayedFrames = 0L
        lastPlayedAtMs = 0L
        stallStartedAtMs = 0L
        writeCallCount = 0L
        lastLogAtMs = SystemClock.elapsedRealtime()
    }

    fun resetWrittenFrames() {
        totalFramesWritten = 0L
    }

    /**
     * Re-anchor the written-frame total on the playhead, so pending latency reads zero.
     *
     * Used after a flush instead of zeroing the total: the playhead is only reset if the flush
     * actually happened, and whether it did depends on the track's play state. Reading the playhead
     * back is right either way, where guessing was wrong half the time.
     */
    fun rebaseToPlayhead(track: AudioTrack) {
        totalFramesWritten = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        lastPlayedFrames = totalFramesWritten
        lastPlayedAtMs = SystemClock.elapsedRealtime()
        stallStartedAtMs = 0L
    }

    fun onTrackCreated(track: AudioTrack) {
        lastRoutedDeviceId = track.routedDevice?.id ?: Int.MIN_VALUE
        reset()
    }

    fun onRoutingChanged(deviceId: Int) {
        lastRoutedDeviceId = deviceId
    }

    fun onFramesWritten(frames: Long) {
        totalFramesWritten += frames
        writeCallCount++
    }

    fun pendingMs(track: AudioTrack, sampleRate: Int, bytesPerFrame: Int): Int {
        val pendingFrames = pendingFrames(track, sampleRate)
        return (pendingFrames * 1000L / sampleRate).toInt().coerceAtLeast(0)
    }

    fun stalledMs(track: AudioTrack, sampleRate: Int, bytesPerFrame: Int): Long {
        val played = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        val now = SystemClock.elapsedRealtime()
        if (played != lastPlayedFrames) {
            lastPlayedFrames = played
            lastPlayedAtMs = now
            stallStartedAtMs = 0L
            return 0L
        }
        if (pendingFrames(track, sampleRate) <= sampleRate / 20) {
            stallStartedAtMs = 0L
            return 0L
        }
        if (stallStartedAtMs == 0L) {
            stallStartedAtMs = now
        }
        return now - stallStartedAtMs
    }

    fun clearStall() {
        stallStartedAtMs = 0L
    }

    fun maybeLogStats(track: AudioTrack?, sampleRate: Int, bytesPerFrame: Int) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogAtMs < LOG_INTERVAL_MS) return
        lastLogAtMs = now
        val t = track ?: return
        val pending = pendingFrames(t, sampleRate)
        Log.i(
            "PhonoAudioTrack",
            "pcm stats writes=$writeCallCount writtenFrames=$totalFramesWritten " +
                "playedFrames=${t.playbackHeadPosition} pendingFrames=$pending " +
                "(~${pending * 1000 / sampleRate}ms)",
        )
    }

    /**
     * Frames written but not yet heard — the output latency the reported position is corrected by.
     *
     * Subtracted in the playhead's own 32-bit space, because `getPlaybackHeadPosition()` wraps every
     * ~27 hours at 44.1 kHz while `totalFramesWritten` does not, and a straight subtraction goes
     * badly wrong at the wrap.
     *
     * Then capped, because the wrap correction is indistinguishable from the two counters having
     * drifted apart, and believing a wrapped value is expensive: the position correction subtracts
     * this from every report, so one bogus reading clamps the progress bar to zero until the track is
     * restarted. `createAndPlayLocked` caps the AudioTrack buffer at three quarters of a second, so
     * anything past a second of pending audio is bookkeeping, not latency.
     */
    private fun pendingFrames(track: AudioTrack, sampleRate: Int): Long {
        val played = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        var pending = (totalFramesWritten and 0xFFFF_FFFFL) - played
        if (pending < 0) pending += 0x1_0000_0000L
        return if (pending > sampleRate.toLong()) 0L else pending
    }
}

private class PhonoAudioSinkCoordinator {
    private val recreating = AtomicBoolean(false)
    @Volatile var lastRecreateAtMs = 0L

    fun tryBeginRecreate(): Boolean = recreating.compareAndSet(false, true)

    fun endRecreate() {
        recreating.set(false)
    }
}
