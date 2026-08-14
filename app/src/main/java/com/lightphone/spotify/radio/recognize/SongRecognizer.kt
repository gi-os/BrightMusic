package com.lightphone.spotify.radio.recognize

import android.content.Context
import android.util.Log
import com.shazam.shazamkit.AudioSampleRateInHz
import com.shazam.shazamkit.DeveloperToken
import com.shazam.shazamkit.DeveloperTokenProvider
import com.shazam.shazamkit.MatchResult
import com.shazam.shazamkit.ShazamKit
import com.shazam.shazamkit.ShazamKitResult
import com.shazam.shazamkit.StreamingSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Names what a station is playing by listening to the stream itself, via ShazamKit.
 *
 * This exists for the stations that publish nothing: WNYU between logged spins (a DJ who is not
 * logging can go hours dark on Spinitron — observed live), and any mount whose Icecast title is a
 * slogan. It is the *fallback*, not the primary: the scraped and API sources are exact and free,
 * so recognition only runs when they have gone stale. See RadioController for the arbitration.
 *
 * ShazamKit's Android SDK does the signature generation on-device from PCM this app feeds it —
 * which is what lets the whole thing work from [StreamSampler]'s url tap instead of a microphone.
 * The developer token comes from [ShazamToken]; no token, no recognition, no errors.
 */
class SongRecognizer(private val context: Context) {

    data class Recognized(val artist: String, val title: String, val artUrl: String?)

    private val sampler = StreamSampler()

    /** One session is meant to live long and match many streams; recreated on token change. */
    private var session: StreamingSession? = null
    private var sessionKey: Pair<String, Int>? = null

    /** Boundary hint from the last sample: true when its audio faded out at the end. */
    var lastTailSilent: Boolean = false
        private set

    fun available(): Boolean = ShazamToken.load(context) != null

    suspend fun recognize(streamUrl: String): Recognized? {
        val token = ShazamToken.load(context) ?: return null
        val sample = sampler.sample(streamUrl) ?: return null
        lastTailSilent = sample.tailSilent
        val rate = when (sample.sampleRateHz) {
            48_000 -> AudioSampleRateInHz.SAMPLE_RATE_48000
            44_100 -> AudioSampleRateInHz.SAMPLE_RATE_44100
            32_000 -> AudioSampleRateInHz.SAMPLE_RATE_32000
            16_000 -> AudioSampleRateInHz.SAMPLE_RATE_16000
            else -> return null // broadcast MP3/AAC is one of these; anything else is not ours
        }
        val live = obtainSession(token, sample.sampleRateHz, rate) ?: return null
        return withTimeoutOrNull(MATCH_TIMEOUT_MS) {
            coroutineScope {
                // Collector first (undispatched, so it is already subscribed), then the audio:
                // results only flow while something listens, and a result that arrives before
                // the collector would simply be missed.
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    live.recognitionResults().first()
                }
                val bytesPerMs = sample.sampleRateHz * 2 / 1000
                val chunk = bytesPerMs * 1000 // ~1s per call, the shape a mic feed would have
                var offset = 0
                var timestampMs = 0L
                while (offset < sample.pcm.size) {
                    val len = minOf(chunk, sample.pcm.size - offset)
                    live.matchStream(sample.pcm.copyOfRange(offset, offset + len), len, timestampMs)
                    offset += len
                    timestampMs += len / bytesPerMs
                }
                when (val r = result.await()) {
                    is MatchResult.Match -> r.matchedMediaItems.firstOrNull()?.let { item ->
                        val artist = item.artist.orEmpty()
                        val title = item.title.orEmpty()
                        if (artist.isBlank() || title.isBlank()) {
                            null // a nameless match cannot be shown or searched
                        } else {
                            Recognized(artist, title, item.artworkURL?.toString())
                        }
                    }
                    is MatchResult.NoMatch -> null
                    is MatchResult.Error -> {
                        // A bad or expired token fails here, not at session creation. Drop the
                        // session so the next attempt rebuilds against the current token.
                        Log.w(TAG, "match error: ${r.exception.message}")
                        session = null
                        sessionKey = null
                        null
                    }
                }
            }
        }
    }

    private suspend fun obtainSession(
        token: String,
        rateHz: Int,
        rate: AudioSampleRateInHz,
    ): StreamingSession? {
        val key = token to rateHz
        session?.takeIf { sessionKey == key }?.let { return it }
        val catalog = ShazamKit.createShazamCatalog(
            DeveloperTokenProvider { DeveloperToken(token) },
        )
        return when (val r = ShazamKit.createStreamingSession(catalog, rate, READ_BUFFER_SIZE)) {
            is ShazamKitResult.Success -> r.data.also {
                session = it
                sessionKey = key
            }
            is ShazamKitResult.Failure -> {
                Log.w(TAG, "session failed: ${r.reason.message}")
                null
            }
        }
    }

    private companion object {
        const val TAG = "SongRecognizer"
        const val READ_BUFFER_SIZE = 8192
        /** Sampling is done by now; this bounds only the signature match round-trips. */
        const val MATCH_TIMEOUT_MS = 20_000L
    }
}
