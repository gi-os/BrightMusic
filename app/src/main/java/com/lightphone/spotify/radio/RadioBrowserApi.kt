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
 * [radio-browser.info](https://www.radio-browser.info) — a community station directory, ~50k stations,
 * no key and no account. This is what makes "local radio" a search box rather than a hardcoded list.
 *
 * ### Why not DNS SRV
 * The documented way to pick a mirror is an SRV lookup on `_api._tcp.radio-browser.info`. Android has
 * no SRV resolver in the platform API (`InetAddress` does A/AAAA only) and pulling in dnsjava for one
 * lookup is not worth it, so this uses **`all.api.radio-browser.info`**, which is round-robin A records
 * across the same mirrors. `/json/servers` is fetched once as a fallback list for when that host itself
 * is unreachable — at the time of writing it returns a single distinct mirror, so this matters more in
 * theory than in practice, but it costs one request per process.
 *
 * ### The User-Agent is not optional
 * The directory asks every client to identify itself and rate-limits by UA. An anonymous or default
 * `okhttp/...` UA is how an app ends up blocked, so [USER_AGENT] is sent on every call.
 *
 * ### `url_resolved`, never `url`
 * `url` is what the submitter typed and is often a `.pls`/`.m3u` playlist. `url_resolved` is what the
 * directory's own checker followed it to — a real audio endpoint — which is the only one `MediaPlayer`
 * can be handed directly.
 */
class RadioBrowserApi {

    /** One station as the directory describes it, before it becomes a [RadioStation]. */
    data class Result(
        val uuid: String,
        val name: String,
        val streamUrl: String,
        val faviconUrl: String?,
        val codec: String?,
        val bitrate: Int,
        val country: String?,
        val state: String?,
        val tags: List<String>,
        val votes: Int,
        val isHls: Boolean,
    ) {
        /**
         * `MP3 128k · New York` — assembled here rather than in the row so the search list and a saved
         * favourite read identically.
         */
        val subtitle: String
            get() = buildList {
                codec?.takeIf { it.isNotBlank() }?.let {
                    add(if (bitrate > 0) "$it ${bitrate}k" else it)
                }
                state?.takeIf { it.isNotBlank() }?.let { add(it) }
                    ?: country?.takeIf { it.isNotBlank() }?.let { add(it) }
            }.joinToString(" · ").ifBlank { "Radio" }

        fun toStation(): RadioStation = RadioStation(
            id = uuid,
            title = name,
            url = streamUrl,
            subtitle = subtitle,
            artworkUrl = faviconUrl,
            // The mount is the stream's own path, which is what status-json.xsl keys its sources by.
            metadata = RadioStation.MetadataSource.IcecastStatus(
                runCatching { Uri.parse(streamUrl).path.orEmpty() }.getOrDefault(""),
            ),
            origin = RadioStation.Origin.Directory,
        )
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Mirrors learned from `/json/servers`, tried in order after [PRIMARY_HOST]. */
    @Volatile
    private var mirrors: List<String> = emptyList()

    /**
     * Stations matching [query] by name, most-voted first.
     *
     * `hidebroken=true` drops anything the directory's checker could not reach on its last pass. That
     * is the single most valuable parameter here: community-submitted stream URLs rot constantly, and
     * without it roughly a third of results are dead links the user has to discover by tapping them.
     */
    suspend fun search(query: String, limit: Int = 40): List<Result> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        return get(
            "json/stations/search" +
                "?name=${Uri.encode(trimmed)}" +
                "&hidebroken=true&order=votes&reverse=true&limit=$limit",
        )
    }

    /** The most-voted stations for a US state, which is what "local radio" means without a search. */
    suspend fun byState(state: String, limit: Int = 40): List<Result> = get(
        "json/stations/search" +
            "?countrycode=US&state=${Uri.encode(state)}" +
            "&hidebroken=true&order=votes&reverse=true&limit=$limit",
    )

    /**
     * Re-read a saved station, so a favourite whose stream URL the directory has since corrected keeps
     * working. Null when the uuid is gone (a removed station) — the caller keeps the stored copy rather
     * than deleting the user's favourite over one failed request.
     */
    suspend fun byUuid(uuid: String): Result? =
        get("json/stations/byuuid/${Uri.encode(uuid)}").firstOrNull()

    /**
     * Tell the directory a station was played. This is what feeds the click counts the whole thing is
     * ranked by, so an app that searches but never reports is freeloading on other people's votes.
     * Fire-and-forget: the response is not read and a failure is not surfaced.
     */
    suspend fun reportClick(uuid: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                http.newCall(request("json/url/${Uri.encode(uuid)}", PRIMARY_HOST)).execute().close()
            }
        }
    }

    private suspend fun get(path: String): List<Result> = withContext(Dispatchers.IO) {
        for (host in hosts()) {
            val body = runCatching {
                http.newCall(request(path, host)).execute().use {
                    if (it.isSuccessful) it.body?.string() else null
                }
            }.getOrNull()
            if (body != null) {
                return@withContext runCatching { parse(body) }.getOrElse {
                    Log.w(TAG, "parse failed for $path", it)
                    emptyList()
                }
            }
            Log.w(TAG, "$host failed for $path")
        }
        emptyList()
    }

    private fun request(path: String, host: String) = Request.Builder()
        .url("https://$host/$path")
        .header("User-Agent", USER_AGENT)
        .build()

    /** [PRIMARY_HOST] first, then any mirror learned earlier, then a discovery attempt. */
    private fun hosts(): List<String> {
        val known = mirrors
        if (known.isNotEmpty()) return (listOf(PRIMARY_HOST) + known).distinct()
        discoverMirrors()
        return (listOf(PRIMARY_HOST) + mirrors).distinct()
    }

    private fun discoverMirrors() {
        val body = runCatching {
            http.newCall(request("json/servers", PRIMARY_HOST)).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            }
        }.getOrNull() ?: return
        mirrors = runCatching {
            val array = JSONArray(body)
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun parse(body: String): List<Result> {
        val array = JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val uuid = o.optString("stationuuid").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val url = o.string("url_resolved") ?: o.string("url") ?: return@mapNotNull null
            Result(
                uuid = uuid,
                name = name,
                streamUrl = url,
                faviconUrl = o.string("favicon"),
                codec = o.string("codec"),
                bitrate = o.optInt("bitrate", 0),
                country = o.string("country"),
                state = o.string("state"),
                tags = o.string("tags")?.split(',')?.mapNotNull { t ->
                    t.trim().takeIf(String::isNotEmpty)
                }.orEmpty(),
                votes = o.optInt("votes", 0),
                isHls = o.optInt("hls", 0) == 1,
            )
        }
    }

    private fun JSONObject.string(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private companion object {
        const val TAG = "RadioBrowserApi"

        /** Round-robin across every mirror; see the class doc on why this is not an SRV lookup. */
        const val PRIMARY_HOST = "all.api.radio-browser.info"

        const val USER_AGENT = "LightPhono/1.0 (+https://github.com/gi-os/LightPhono)"
    }
}
