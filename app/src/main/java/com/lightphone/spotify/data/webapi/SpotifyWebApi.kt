package com.lightphone.spotify.data.webapi

import com.lightphone.spotify.data.PagedResponse
import com.lightphone.spotify.data.SpotifyAlbumDetail
import com.lightphone.spotify.data.SpotifyAlbumSimple
import com.lightphone.spotify.data.SpotifyArtistDetail
import com.lightphone.spotify.data.SpotifyCurrentUser
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifyPublicUser
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SpotifySavedTrack
import com.lightphone.spotify.data.SpotifySearchResults
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.TopTracksResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Spotify Web API client for api.spotify.com. Uses the user's dev-app OAuth
 * tokens from [WebApiAuth]. Honors Retry-After on 429.
 */
class SpotifyWebApi(
    private val auth: WebApiAuth,
    private val baseUrl: String = BASE_URL,
) {

    companion object {
        private const val BASE_URL = "https://api.spotify.com/v1"
        private const val MAX_429_RETRIES = 4
        private const val DEFAULT_SEARCH_LIMIT = 8
        private const val MAX_REMOTE_URIS = 200

        /**
         * Episodes fetched per show. Enough for months of a weekly feed, bounded because a
         * long-running daily show has thousands and the screen only ever shows recent ones.
         */
        private const val EPISODE_PAGE_LIMIT = 50
        const val LIBRARY_PAGE_LIMIT = 50
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // Spotify may send explicit null for string fields on unavailable/local albums (e.g. playlist items).
        coerceInputValues = true
    }

    private val jsonMediaType = "application/json".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .authenticator(object : Authenticator {
            override fun authenticate(route: Route?, response: Response): Request? {
                if (responseCount(response) >= 2) return null
                val bearer = try {
                    auth.refreshBearerAfterUnauthorized() ?: return null
                } catch (_: WebApiAuthException) {
                    return null
                }
                return response.request.newBuilder()
                    .header("Authorization", "Bearer $bearer")
                    .build()
            }
        })
        .build()

    suspend fun savedTracksPage(
        offset: Int,
        limit: Int = LIBRARY_PAGE_LIMIT,
    ): LibraryPage<SpotifySavedTrack> {
        val pageLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val path = "/me/tracks?limit=$pageLimit&offset=$safeOffset&market=from_token"
        val page = getSuspend<PagedResponse<SpotifySavedTrack?>>(path)
        return LibraryPage(
            items = page.items.filterNotNull().filter { it.track != null },
            total = page.total,
            offset = safeOffset,
        )
    }

    suspend fun savedAlbumsPage(
        offset: Int,
        limit: Int = LIBRARY_PAGE_LIMIT,
    ): LibraryPage<SpotifySavedAlbum> {
        val pageLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val path = "/me/albums?limit=$pageLimit&offset=$safeOffset&market=from_token"
        val page = getSuspend<PagedResponse<SpotifySavedAlbum?>>(path)
        return LibraryPage(
            items = page.items.filterNotNull().filter { it.album != null },
            total = page.total,
            offset = safeOffset,
        )
    }

    fun album(albumId: String): SpotifyAlbumDetail {
        val detail = get<SpotifyAlbumDetail>("/albums/$albumId")
        if (detail.tracks.items.isNotEmpty() && detail.tracks.total <= detail.tracks.items.size) {
            return detail
        }
        val allTracks = paginateTracks("/albums/$albumId/tracks", limit = 500)
        return detail.copy(tracks = PagedResponse(items = allTracks, total = allTracks.size))
    }

    fun artist(artistId: String): SpotifyArtistDetail =
        get("/artists/$artistId")

    fun artistAlbums(artistId: String, limit: Int = 50): List<SpotifyAlbumSimple> =
        paginateAlbums(
            path = "/artists/$artistId/albums",
            limit = limit.coerceIn(1, 50),
            extraQuery = mapOf("include_groups" to "album,single"),
        )

    fun artistTopTracks(artistId: String, limit: Int = 10): List<SpotifyTrack> =
        get<TopTracksResponse>(
            "/artists/$artistId/top-tracks?market=from_token&limit=${limit.coerceIn(1, 10)}",
        ).tracks

    fun userProfile(userId: String): SpotifyPublicUser = get("/users/$userId")

    fun track(trackId: String): SpotifyTrack = get("/tracks/$trackId")

    fun search(query: String, limitPerType: Int = DEFAULT_SEARCH_LIMIT): SpotifySearchResults {
        val limit = limitPerType.coerceIn(1, 10)
        val path = buildString {
            append("/search?q=").append(urlEncode(query))
            append("&type=artist,album,track,playlist")
            append("&limit=").append(limit)
            append("&market=from_token")
        }
        return get(path)
    }

    fun saveLibrary(uris: List<String>) {
        if (uris.isEmpty()) return
        put(libraryUrisPath(uris))
    }

    fun removeLibrary(uris: List<String>) {
        if (uris.isEmpty()) return
        delete(libraryUrisPath(uris))
    }

    fun libraryContains(uris: List<String>): List<Boolean> {
        if (uris.isEmpty()) return emptyList()
        return getRaw(libraryUrisPath(uris, contains = true)).let { body ->
            json.decodeFromString<List<Boolean>>(body)
        }
    }

    fun currentUser(): SpotifyCurrentUser = get("/me")

    suspend fun currentUserSuspend(): SpotifyCurrentUser = getSuspend("/me")

    /** Legacy fallback when Step 1 session is unavailable during library sync. */
    suspend fun savedPlaylistsPage(
        offset: Int,
        limit: Int = LIBRARY_PAGE_LIMIT,
    ): LibraryPage<SpotifyPlaylistSimple> {
        val pageLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val page = getSuspend<PagedResponse<SpotifyPlaylistSimple?>>(
            "/me/playlists?limit=$pageLimit&offset=$safeOffset",
        )
        return LibraryPage(
            items = page.items.filterNotNull().filter { it.id.isNotBlank() },
            total = page.total,
            offset = safeOffset,
        )
    }

    // --- Podcasts -----------------------------------------------------------
    //
    // `user-library-read` already covers saved shows, so podcasts need no new scope and no
    // re-authorize.

    suspend fun savedShowsPage(
        offset: Int,
        limit: Int = LIBRARY_PAGE_LIMIT,
    ): LibraryPage<SpotifySavedShow> {
        val pageLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
        val safeOffset = offset.coerceAtLeast(0)
        val page = getSuspend<PagedResponse<SpotifySavedShow?>>(
            "/me/shows?limit=$pageLimit&offset=$safeOffset&market=from_token",
        )
        return LibraryPage(
            items = page.items.filterNotNull().filter { it.show != null },
            total = page.total,
            offset = safeOffset,
        )
    }

    /**
     * Episodes for a show, newest first — which is the order Spotify returns and the order a podcast
     * listener wants. [limit] is capped because a long-running show can have thousands and the screen
     * only ever shows the recent ones.
     */
    suspend fun show(showId: String): SpotifyShow =
        withContext(Dispatchers.IO) { getSuspend("/shows/$showId?market=from_token") }

    suspend fun showEpisodes(showId: String, limit: Int = EPISODE_PAGE_LIMIT): List<SpotifyEpisode> =
        withContext(Dispatchers.IO) {
            val pageLimit = limit.coerceIn(1, LIBRARY_PAGE_LIMIT)
            val page = getSuspend<PagedResponse<SpotifyEpisode?>>(
                "/shows/$showId/episodes?limit=$pageLimit&offset=0&market=from_token",
            )
            page.items.filterNotNull().filter { it.id.isNotBlank() }
        }

    // --- Spotify Connect ----------------------------------------------------
    //
    // These are the only endpoints in this client that talk to *other* devices. They
    // are all suspend (no runBlocking) because they are called from UI event handlers
    // and a poll loop, and every one of them can 404 when the target disappears.

    /** Devices Spotify currently sees for this account. Empty list is normal and common. */
    suspend fun devices(): List<SpotifyDevice> = withContext(Dispatchers.IO) {
        val body = executeConnect(authorizedRequest("/me/player/devices").build())
        if (body.isBlank()) emptyList() else json.decodeFromString<SpotifyDevicesResponse>(body).devices
    }

    /**
     * Current remote playback state, or null when Spotify has no active session at all.
     *
     * The 204-with-empty-body case is the "nothing playing anywhere" signal and is
     * mapped to null; do not treat it as an error.
     */
    suspend fun playerState(): SpotifyPlayerState? = withContext(Dispatchers.IO) {
        val body = executeConnect(authorizedRequest("/me/player?market=from_token").build())
        if (body.isBlank()) null else json.decodeFromString<SpotifyPlayerState>(body)
    }

    /**
     * Hand the account's playback to [deviceId].
     *
     * Spotify's docs say `play` is optional and preserves the current state, but in
     * practice transferring to an idle device with `play=false` leaves nothing playing
     * and no way to start it from the target, so callers generally pass true.
     */
    suspend fun transferPlayback(deviceId: String, play: Boolean = true) = withContext(Dispatchers.IO) {
        val payload = """{"device_ids":["$deviceId"],"play":$play}"""
        executeConnect(authorizedRequest("/me/player").put(payload.toRequestBody(jsonMediaType)).build())
        Unit
    }

    suspend fun remotePlay(deviceId: String?) = connectAction("/me/player/play", deviceId, Method.PUT)

    suspend fun remotePause(deviceId: String?) = connectAction("/me/player/pause", deviceId, Method.PUT)

    suspend fun remoteNext(deviceId: String?) = connectAction("/me/player/next", deviceId, Method.POST)

    suspend fun remotePrevious(deviceId: String?) =
        connectAction("/me/player/previous", deviceId, Method.POST)

    suspend fun remoteSeek(positionMs: Long, deviceId: String?) = connectAction(
        "/me/player/seek?position_ms=${positionMs.coerceAtLeast(0)}",
        deviceId,
        Method.PUT,
    )

    suspend fun remoteShuffle(enabled: Boolean, deviceId: String?) =
        connectAction("/me/player/shuffle?state=$enabled", deviceId, Method.PUT)

    /** [state] is Spotify's vocabulary: "off", "context", or "track". */
    suspend fun remoteRepeat(state: String, deviceId: String?) =
        connectAction("/me/player/repeat?state=$state", deviceId, Method.PUT)

    suspend fun remoteVolume(percent: Int, deviceId: String?) = connectAction(
        "/me/player/volume?volume_percent=${percent.coerceIn(0, 100)}",
        deviceId,
        Method.PUT,
    )

    /**
     * Start [uris] on a remote device.
     *
     * Sends an explicit `uris` array rather than a `context_uri`, so the remote queue
     * matches what LightPhono showed the user — handing Spotify a context lets it
     * pick its own order once shuffle is on.
     */
    suspend fun remotePlayUris(
        uris: List<String>,
        offsetIndex: Int = 0,
        positionMs: Long = 0,
        deviceId: String?,
    ) = withContext(Dispatchers.IO) {
        // Spotify rejects oversized bodies; the first 200 entries are plenty for a
        // remote handoff and the user can re-send from the target if they need more.
        val trimmed = uris.take(MAX_REMOTE_URIS)
        val payload = buildString {
            append("""{"uris":[""")
            append(trimmed.joinToString(",") { "\"$it\"" })
            append("""],"offset":{"position":${offsetIndex.coerceIn(0, maxOf(trimmed.size - 1, 0))}}""")
            append(""","position_ms":${positionMs.coerceAtLeast(0)}}""")
        }
        val path = "/me/player/play".withDevice(deviceId)
        executeConnect(authorizedRequest(path).put(payload.toRequestBody(jsonMediaType)).build())
        Unit
    }

    private enum class Method { PUT, POST }

    private suspend fun connectAction(path: String, deviceId: String?, method: Method) =
        withContext(Dispatchers.IO) {
            val empty = ByteArray(0).toRequestBody(null)
            val builder = authorizedRequest(path.withDevice(deviceId))
            val request = when (method) {
                Method.PUT -> builder.put(empty)
                Method.POST -> builder.post(empty)
            }.build()
            executeConnect(request)
            Unit
        }

    private fun String.withDevice(deviceId: String?): String {
        if (deviceId.isNullOrBlank()) return this
        val separator = if (contains('?')) "&" else "?"
        return "$this${separator}device_id=$deviceId"
    }

    /**
     * Like [executeWithRetry], but translates the Connect-specific status codes into
     * typed failures instead of a generic `IOException`, because each one needs a
     * different response from the UI:
     *
     *  - **404** the device list is stale — refresh it rather than showing an error
     *  - **403** either a restricted device or a token minted before this fork added
     *    the player scopes, which needs a re-authorize, not a retry
     */
    private suspend fun executeConnect(request: Request): String {
        var lastResponse: Response? = null
        for (attempt in 0 until MAX_429_RETRIES) {
            lastResponse?.close()
            lastResponse = client.newCall(request).execute()
            val response = lastResponse!!
            val body = response.body?.string() ?: ""
            when {
                response.code == 429 -> {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 2L
                    delay(retryAfter.coerceIn(1, 30) * 1000)
                    continue
                }
                response.isSuccessful -> {
                    response.close()
                    return body
                }
                response.code == 401 -> throw WebApiAuthException(
                    "Web API unauthorized — re-authorize Step 2",
                )
                response.code == 404 -> {
                    response.close()
                    throw ConnectNoActiveDeviceException()
                }
                response.code == 403 -> {
                    response.close()
                    throw if (body.contains("scope", ignoreCase = true)) {
                        ConnectScopeException()
                    } else {
                        ConnectRestrictedException(body)
                    }
                }
                else -> {
                    response.close()
                    throw IOException("HTTP ${response.code}: $body")
                }
            }
        }
        lastResponse?.close()
        throw IOException("HTTP 429: rate limited after $MAX_429_RETRIES retries")
    }

    private fun paginateTracks(path: String, limit: Int): List<SpotifyTrack> {
        val results = mutableListOf<SpotifyTrack>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (results.size < limit && offset < total) {
            val pageLimit = minOf(LIBRARY_PAGE_LIMIT, limit - results.size)
            val fullPath = "$path?limit=$pageLimit&offset=$offset"
            val page = get<PagedResponse<SpotifyTrack>>(fullPath)
            total = page.total
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (offset >= total || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private fun paginateAlbums(
        path: String,
        limit: Int,
        extraQuery: Map<String, String> = emptyMap(),
    ): List<SpotifyAlbumSimple> {
        val results = mutableListOf<SpotifyAlbumSimple>()
        var offset = 0
        var total = Int.MAX_VALUE
        while (results.size < limit && offset < total) {
            val pageLimit = minOf(LIBRARY_PAGE_LIMIT, limit - results.size)
            val query = buildString {
                append("?limit=").append(pageLimit)
                append("&offset=").append(offset)
                extraQuery.forEach { (k, v) ->
                    append("&").append(k).append("=").append(urlEncode(v))
                }
            }
            val page = get<PagedResponse<SpotifyAlbumSimple>>("$path$query")
            total = page.total
            if (page.items.isEmpty()) break
            results.addAll(page.items)
            offset += page.items.size
            if (offset >= total || page.items.size < pageLimit) break
        }
        return results.take(limit)
    }

    private inline fun <reified T> get(path: String): T {
        val body = getRaw(path)
        return json.decodeFromString(body)
    }

    private suspend inline fun <reified T> getSuspend(path: String): T {
        val body = getRawSuspend(path)
        return json.decodeFromString(body)
    }

    private fun getRaw(path: String): String {
        val request = authorizedRequest(path).build()
        return kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private suspend fun getRawSuspend(path: String): String = withContext(Dispatchers.IO) {
        val request = authorizedRequest(path).build()
        executeWithRetry(request)
    }

    private fun put(path: String) {
        val request = authorizedRequest(path)
            .put(ByteArray(0).toRequestBody(null))
            .build()
        kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun put(path: String, jsonBody: String) {
        val request = authorizedRequest(path)
            .put(jsonBody.toRequestBody(jsonMediaType))
            .build()
        kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun delete(path: String) {
        val request = authorizedRequest(path).delete().build()
        kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun delete(path: String, jsonBody: String) {
        val request = authorizedRequest(path)
            .delete(jsonBody.toRequestBody(jsonMediaType))
            .build()
        kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private inline fun <reified T> post(path: String, jsonBody: String): T {
        val body = postRaw(path, jsonBody)
        return if (body.isBlank()) {
            json.decodeFromString("{}")
        } else {
            json.decodeFromString(body)
        }
    }

    private inline fun <reified T> putReturning(path: String, jsonBody: String): T {
        val body = putRaw(path, jsonBody)
        return if (body.isBlank()) {
            json.decodeFromString("{}")
        } else {
            json.decodeFromString(body)
        }
    }

    private fun postRaw(path: String, jsonBody: String): String {
        val request = authorizedRequest(path)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()
        return kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun putRaw(path: String, jsonBody: String): String {
        val request = authorizedRequest(path)
            .put(jsonBody.toRequestBody(jsonMediaType))
            .build()
        return kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun deleteReturning(path: String, jsonBody: String): String {
        val request = authorizedRequest(path)
            .delete(jsonBody.toRequestBody(jsonMediaType))
            .build()
        return kotlinx.coroutines.runBlocking { executeWithRetry(request) }
    }

    private fun authorizedRequest(path: String): Request.Builder {
        require(path.startsWith("/")) {
            "Web API path must be relative to baseUrl, got: $path"
        }
        return Request.Builder()
            .url("$baseUrl$path")
            .header("Authorization", "Bearer ${auth.currentBearer()}")
            .header("Accept", "application/json")
    }

    private suspend fun executeWithRetry(request: Request): String {
        var lastResponse: Response? = null
        for (attempt in 0 until MAX_429_RETRIES) {
            lastResponse?.close()
            lastResponse = client.newCall(request).execute()
            val response = lastResponse!!
            val body = response.body?.string() ?: ""
            when {
                response.code == 429 -> {
                    val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 2L
                    delay(retryAfter.coerceIn(1, 30) * 1000)
                    continue
                }
                response.isSuccessful -> {
                    response.close()
                    return body
                }
                response.code == 401 -> throw WebApiAuthException(
                    "Web API unauthorized — re-authorize Step 2",
                )
                else -> {
                    response.close()
                    throw IOException("HTTP ${response.code}: $body")
                }
            }
        }
        lastResponse?.close()
        throw IOException("HTTP 429: rate limited after $MAX_429_RETRIES retries")
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private fun libraryUrisPath(uris: List<String>, contains: Boolean = false): String {
        val encoded = uris.joinToString(",") { urlEncode(it) }
        val base = if (contains) "/me/library/contains" else "/me/library"
        return "$base?uris=$encoded"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}
