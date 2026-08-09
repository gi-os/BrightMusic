package com.lightphone.spotify.radio

import android.util.Log
import java.util.concurrent.TimeUnit
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

    fun nowPlaying(source: StationMetadata.Source): NtsApi.NowPlaying? = when (source) {
        StationMetadata.Source.SPINITRON_WNYU -> wnyu()
        StationMetadata.Source.WNYC -> wnyc()
        StationMetadata.Source.NONE -> null
    }

    private fun wnyu(): NtsApi.NowPlaying? {
        val fromCache = cachedPlaylistUrl?.let { url -> get(url)?.let(StationMetadata::latestSpin) }
        if (fromCache != null) {
            return NtsApi.NowPlaying(title = fromCache, artworkUrl = StationMetadata.WNYU_LOGO)
        }
        // Either nothing cached, or the show ended and its playlist stopped growing. Find the
        // current one again.
        val station = get(StationMetadata.SPINITRON_STATION_URL) ?: return null
        val playlistUrl = StationMetadata.newestPlaylistUrl(station) ?: return null
        cachedPlaylistUrl = playlistUrl
        val spin = get(playlistUrl)?.let(StationMetadata::latestSpin) ?: return null
        return NtsApi.NowPlaying(title = spin, artworkUrl = StationMetadata.WNYU_LOGO)
    }

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
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile"
    }
}
