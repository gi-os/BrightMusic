package com.lightphone.spotify.radio

import android.util.Log
import com.lightphone.spotify.playback.lockscreen.AppVisibility
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the two special-cased stations' now-playing. Parsing lives in [StationMetadata].
 *
 * WNYU costs two requests — the station page to find the live playlist, then the playlist itself
 * — because Spinitron has no key-free endpoint that answers "what is on right now": its JSON API
 * wants a per-station key, and every widget URL is JavaScript-rendered. The playlist *pages* are
 * server-rendered, so those are what this reads. At one poll every 30 seconds that is two small
 * requests a minute, which is why the playlist url is remembered between polls: while a show is
 * on it does not change, so the station page is only re-read when the playlist stops answering.
 */
class StationMetadataApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** The live playlist, remembered so most polls are one request rather than two. */
    private var cachedPlaylistUrl: String? = null

    /** When [cachedPlaylistUrl] was last resolved from the station page. */
    private var playlistResolvedAtMs: Long = 0L

    /**
     * Suspend, on the IO dispatcher — like [NtsApi] and [IcecastApi], and unlike the first cut of
     * this, which was a plain function doing blocking IO on whatever thread the metadata loop ran
     * on. That is the main thread, so every request threw NetworkOnMainThreadException straight
     * into the `runCatching` in [get] and came back null. Nothing logged, nothing shown, and the
     * feature looked simply absent.
     */
    suspend fun nowPlaying(source: StationMetadata.Source): NtsApi.NowPlaying? =
        withContext(Dispatchers.IO) {
            when (source) {
                StationMetadata.Source.SPINITRON_WNYU -> wnyu()
                StationMetadata.Source.WNYC -> wnyc()
                StationMetadata.Source.NONE -> null
            }
        }

    private fun wnyu(): NtsApi.NowPlaying? {
        // The pin expires on a clock, not on failure. The first cut of this waited for the
        // playlist to "stop answering" — but a finished Spinitron playlist answers forever, so
        // once a show ended the label froze on its last song for as long as the radio played.
        // Time is the only signal there is that a new show may have its own playlist by now.
        //
        // The clock runs faster while someone is looking. With the app on screen the pin is
        // re-resolved on every poll — the label should never sit a show behind while it is
        // being read — and the cache only earns its keep in the background, where a stale
        // few minutes costs nothing and the halved request rate does the battery a favour.
        val ttl = if (AppVisibility.foreground) PLAYLIST_TTL_VISIBLE_MS else PLAYLIST_TTL_MS
        val fresh = System.currentTimeMillis() - playlistResolvedAtMs < ttl
        val fromCache = if (fresh) {
            cachedPlaylistUrl?.let { url -> get(url)?.let(StationMetadata::latestSpin) }
        } else {
            null
        }
        if (fromCache != null) return fromCache.asNowPlaying()
        // Nothing cached, the pin is stale, or the playlist gave no spins. Find the current one.
        val playlistUrl = get(StationMetadata.SPINITRON_STATION_URL)
            ?.let(StationMetadata::newestPlaylistUrl)
        // The station page being down is no reason to go silent while the pin still answers.
            ?: cachedPlaylistUrl
            ?: return null
        cachedPlaylistUrl = playlistUrl
        playlistResolvedAtMs = System.currentTimeMillis()
        val spin = get(playlistUrl)?.let(StationMetadata::latestSpin) ?: return null
        return spin.asNowPlaying()
    }

    /**
     * The spin's own cover when the page had one, otherwise nothing — deliberately not the
     * station logo, which used to ride along here and outrank the Spotify match's album art in
     * the player. Null lets [RadioController] fall back to station art and the match's cover
     * take the screen; see `withRadio`.
     */
    private fun StationMetadata.Spin.asNowPlaying() =
        NtsApi.NowPlaying(title = text, artworkUrl = coverUrl)

    private fun wnyc(): NtsApi.NowPlaying? {
        val body = get(StationMetadata.WNYC_WHATS_ON_URL) ?: return null
        val now = StationMetadata.parseWnyc(body) ?: return null
        return NtsApi.NowPlaying(title = now.text, artworkUrl = now.artUrl)
    }

    private fun get(url: String): String? = runCatching {
        // A browser user-agent: Spinitron serves a different, JavaScript-only page to clients it
        // does not recognise, and the server-rendered spins are the entire point of reading it.
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        }
    }.onFailure { Log.w(TAG, "fetch failed: $url (${it.message})") }.getOrNull()

    private companion object {
        const val TAG = "StationMetadata"

        /**
         * How long a resolved playlist is trusted with the app in the background. Shows run an
         * hour, so five minutes bounds how far the label can lag a show change, at the price of
         * one extra request per expiry.
         */
        const val PLAYLIST_TTL_MS = 5 * 60_000L

        /**
         * And with the app on screen: shorter than the 30s poll, so every visible check
         * re-resolves. "Which playlist is live" costs one small server-rendered page.
         */
        const val PLAYLIST_TTL_VISIBLE_MS = 25_000L
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile"
    }
}
