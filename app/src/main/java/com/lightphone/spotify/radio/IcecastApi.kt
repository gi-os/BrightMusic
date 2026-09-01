package com.lightphone.spotify.radio

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * What is on air on a generic Icecast station.
 *
 * ### Why this exists at all
 * An Icecast stream carries its current title *in band*, interleaved with the audio every
 * `icy-metaint` bytes, and every desktop player shows it. `MediaPlayer` has **no API for it** — it does
 * not send `Icy-MetaData: 1`, does not expose `StreamTitle`, and
 * `MEDIA_INFO_METADATA_UPDATE`/`getMetadata` were removed long before this app's `minSdk`. Reading it
 * would mean opening the socket ourselves, stripping the metadata blocks, and feeding the remaining
 * bytes to the player through a local proxy — a lot of machinery for a subtitle.
 *
 * So the title is fetched out of band instead, from Icecast's own `status-json.xsl`. It is served by
 * every default Icecast 2.4+ install on the same host and port as the stream, and it lists every mount
 * with its `title`.
 *
 * ### It is allowed to fail
 * Plenty of stations are not Icecast (Shoutcast, StreamGuys, Wowza, a CDN in front of any of them), sit
 * behind a proxy that hides the admin endpoint, or run with it disabled. Every path here degrades to
 * null, and [RadioController] treats null as "no title" rather than an error — same contract as the NTS
 * Firestore lookup. A station with no title still plays; it just shows its own name.
 */
class IcecastApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    /**
     * Hosts that have already answered with something unusable. Without this the metadata loop keeps
     * hammering a station that will never answer, every 30s, for as long as it is playing.
     */
    private val unsupported = mutableSetOf<String>()

    /**
     * Current title for [streamUrl]'s mount, or null.
     *
     * [mount] is the stream URL's path. It is matched against each source's `listenurl` rather than
     * trusted blindly, because a host serving several mounts returns them all and picking the first
     * would show the wrong station's track.
     */
    suspend fun nowPlaying(streamUrl: String, mount: String): String? = withContext(Dispatchers.IO) {
        val base = runCatching {
            val uri = Uri.parse(streamUrl)
            val scheme = uri.scheme ?: return@runCatching null
            val host = uri.host ?: return@runCatching null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$port"
        }.getOrNull() ?: return@withContext null

        if (base in unsupported) return@withContext null

        // Two kinds of nothing, and only one is evidence. A response that arrived but is unusable
        // (non-2xx, or a body that will not parse below) proves the host does not serve the
        // endpoint — blacklist it. A transport failure (timeout, DNS, dead Wi-Fi) proves nothing
        // about the host, and blacklisting on it silences a working station's titles forever
        // after one blip.
        val body = runCatching {
            http.newCall(Request.Builder().url("$base/status-json.xsl").build()).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            }
        }.getOrElse {
            // Transport failure (timeout, DNS, dead Wi-Fi): try again next poll.
            return@withContext null
        }

        if (body == null) {
            // The host answered non-2xx: content evidence, blacklist.
            unsupported += base
            return@withContext null
        }

        runCatching { parse(body, mount) }.getOrElse {
            Log.w(TAG, "status-json.xsl parse failed for $base", it)
            unsupported += base
            null
        }
    }

    private fun parse(body: String, mount: String): String? {
        val stats = JSONObject(body).optJSONObject("icestats") ?: return null
        // `source` is an object when the server has exactly one mount and an array when it has several.
        // Both shapes are normal Icecast output, not an error, and a client that only handles the array
        // silently shows no title on every single-mount station — which is most of them.
        val sources = when (val raw = stats.opt("source")) {
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            is JSONObject -> listOf(raw)
            else -> return null
        }
        if (sources.isEmpty()) return null

        val match = sources.firstOrNull { source ->
            val listen = source.optString("listenurl")
            mount.isNotBlank() && listen.endsWith(mount)
        } ?: sources.singleOrNull() ?: return null

        // `title` is the full "Artist - Track" line. Some servers leave it empty and fill
        // `yp_currently_playing` instead; `server_name` is the station's own name, never the track, so
        // it is deliberately not a fallback.
        return match.nonBlank("title")
            ?: match.nonBlank("yp_currently_playing")
    }

    private fun JSONObject.nonBlank(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private companion object {
        const val TAG = "IcecastApi"
    }
}
