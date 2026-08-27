package com.lightphone.spotify.playback.download

import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Pacing for the offline-pin queue.
 *
 * Hard-set, deliberately not a user preference: a delay control invites someone to set it to zero,
 * and what that costs is not their download speed, it is their account. 400–1200 ms between tracks
 * serialized the queue but still read as a burst to audio-key and the CDN — below every mature
 * sequential downloader surveyed in docs/download-rate-limiting.md.
 *
 * Note which surface this protects: pins never touch `api.spotify.com`. The 429s come from the audio
 * CDN and the spclient resolve that mints its URL, so a Web API `Retry-After` is the wrong signal to
 * look for and an audio-key timeout is not a rate limit at all.
 */
object DownloadPacing {
    const val TRACK_GAP_MIN_MS = 2_500L
    const val TRACK_GAP_MAX_MS = 5_000L
    const val RATE_LIMIT_COOLDOWN_MS = 20_000L
    const val RATE_LIMIT_RETRY_MAX = 8

    fun isRateLimited(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (looksRateLimited(current.message.orEmpty())) return true
            current = current.cause
        }
        return false
    }

    fun looksRateLimited(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("429") ||
            lower.contains("too many requests") ||
            lower.contains("rate limit") ||
            lower.contains("rate limited") ||
            lower.contains("resource exhausted") ||
            lower.contains("resource_exhausted")
    }

    /** Jittered pause after a pin attempt so the next key/playbackinfo is not immediate. */
    suspend fun afterTrack(): Long {
        val waitMs = Random.nextLong(TRACK_GAP_MIN_MS, TRACK_GAP_MAX_MS + 1)
        delay(waitMs)
        return waitMs
    }

    /** Extra pause when a 429 / rate-limit error is already in hand. */
    suspend fun afterRateLimit() {
        delay(RATE_LIMIT_COOLDOWN_MS)
    }
}
