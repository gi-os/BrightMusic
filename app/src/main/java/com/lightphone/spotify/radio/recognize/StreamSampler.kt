package com.lightphone.spotify.radio.recognize

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A few seconds of a radio station's audio, taken from the *stream url* rather than the speaker.
 *
 * No microphone, no `AudioPlaybackCapture`, no MediaProjection consent dialog: the app already
 * knows where the audio comes from, so it opens its own short connection to the mount, decodes
 * what it needs and hangs up. Icecast serves every client from the live point, so this hears
 * what the listener hears — including when the listener's audio is actually on the HomePods via
 * the OwnTone bridge and the phone itself is silent.
 *
 * MediaExtractor handles the HTTP and the container, MediaCodec the decode — the same silicon
 * that is already playing the station. ~12s at 128kbps is ~190KB of data per sample.
 */
class StreamSampler {

    class Sample(
        /** PCM 16-bit little-endian mono. */
        val pcm: ByteArray,
        val sampleRateHz: Int,
        /** Whether the window fades out at its end — a boundary hint, see [GapDetector]. */
        val tailSilent: Boolean,
    )

    suspend fun sample(url: String, seconds: Int = SAMPLE_SECONDS): Sample? =
        withContext(Dispatchers.IO) {
            runCatching { decode(url, seconds) }
                .onFailure { Log.w(TAG, "sample failed: ${it.message}") }
                .getOrNull()
        }

    private fun decode(url: String, seconds: Int): Sample? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(url)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            val out = ByteArrayOutputStream()
            var monoSamples = 0
            var target = seconds * sampleRate
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            // A stalled live stream must not hold this loop forever: each pass waits 10ms per
            // queue, so this caps a total stall at ~10s before giving up with what we have.
            var stalls = 0
            while (monoSamples < target && stalls < MAX_STALLS) {
                if (!inputDone) {
                    val inIx = codec.dequeueInputBuffer(10_000)
                    if (inIx >= 0) {
                        val buf = codec.getInputBuffer(inIx) ?: continue
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                inIx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outIx = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = codec.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        target = seconds * sampleRate
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> stalls++
                    else -> if (outIx >= 0) {
                        val buf = codec.getOutputBuffer(outIx)
                        if (buf != null && info.size > 0) {
                            monoSamples += appendMono(buf, info, channels, out)
                        }
                        codec.releaseOutputBuffer(outIx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            val pcm = out.toByteArray()
            // Under half a second is not audio worth fingerprinting — a connect that died.
            if (pcm.size < sampleRate) return null
            val shorts = ShortArray(pcm.size / 2)
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
            return Sample(
                pcm = pcm,
                sampleRateHz = sampleRate,
                tailSilent = GapDetector.tailIsSilent(shorts, sampleRate),
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /** Downmix one decoded buffer to mono little-endian PCM16; returns mono samples appended. */
    private fun appendMono(
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        out: ByteArrayOutputStream,
    ): Int {
        val shorts = ShortArray(info.size / 2)
        buf.position(info.offset)
        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        val frames = shorts.size / channels
        val bytes = ByteArray(frames * 2)
        var b = 0
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) acc += shorts[f * channels + c].toInt()
            val mono = (acc / channels).coerceIn(-32768, 32767)
            bytes[b++] = (mono and 0xFF).toByte()
            bytes[b++] = ((mono shr 8) and 0xFF).toByte()
        }
        out.write(bytes)
        return frames
    }

    private companion object {
        const val TAG = "StreamSampler"
        const val SAMPLE_SECONDS = 12
        const val MAX_STALLS = 1000
    }
}
