package com.lightphone.spotify.data.owntone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON = "application/json".toMediaType()
private val owntoneJson = Json { ignoreUnknownKeys = true }

// --- OwnTone JSON API models ---

@Serializable
data class OwntoneOutput(
    val id: String,
    val name: String,
    val type: String,
    val selected: Boolean = false,
    val volume: Int = 50,
    val has_password: Boolean = false,
    val requires_auth: Boolean = false,
)

@Serializable
data class OwntoneOutputsResponse(
    val outputs: List<OwntoneOutput> = emptyList(),
)

@Serializable
data class OwntonePlayerState(
    val state: String = "stop",
    val volume: Int = 50,
    val repeat: String = "off",
    val shuffle: Boolean = false,
    @SerialName("item_id") val itemId: Long = 0,
    @SerialName("item_length_ms") val itemLengthMs: Long = 0,
    @SerialName("item_progress_ms") val itemProgressMs: Long = 0,
)

@Serializable
data class OwntoneOutputUpdate(
    val selected: Boolean,
)

// --- Client ---

class OwntoneApi(
    private val baseUrl: String,
    // Short timeouts on purpose: the bridge lives on the LAN, so anything slower than this is
    // "not home" — and the radio fallback path is blocked until this call gives up.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
) {

    suspend fun listOutputs(): Result<List<OwntoneOutput>> =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder().url("$baseUrl/api/outputs").build()
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body?.string() ?: throw IOException("Empty response")
                    owntoneJson.decodeFromString<OwntoneOutputsResponse>(body).outputs
                }
            }
        }

    suspend fun setOutputSelected(outputId: String, selected: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = owntoneJson.encodeToString(OwntoneOutputUpdate.serializer(), OwntoneOutputUpdate(selected))
                client.newCall(
                    Request.Builder()
                        .url("$baseUrl/api/outputs/$outputId")
                        .put(body.toRequestBody(JSON))
                        .build()
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                }
            }
        }

    suspend fun getPlayerState(): Result<OwntonePlayerState> =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder().url("$baseUrl/api/player").build()
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body?.string() ?: throw IOException("Empty response")
                    owntoneJson.decodeFromString<OwntonePlayerState>(body)
                }
            }
        }

    suspend fun setVolume(volume: Int, outputId: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = if (outputId != null) {
                    "$baseUrl/api/player/volume?volume=$volume&output_id=$outputId"
                } else {
                    "$baseUrl/api/player/volume?volume=$volume"
                }
                // PUT with an empty JSON object — OwnTone returns 400 for a bodyless PUT
                client.newCall(
                    Request.Builder().url(url).put("{}".toRequestBody(JSON)).build()
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                }
            }
        }

    /** Play a URL directly (radio stream, podcast, etc). Clears queue and starts playback immediately. */
    suspend fun playUrl(url: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(url, "UTF-8")
                val apiUrl = "$baseUrl/api/queue/items/add?clear=true&playback=start&uris=$encoded"
                client.newCall(
                    Request.Builder().url(apiUrl).post("{}".toRequestBody(JSON)).build()
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                }
            }
        }

    /** Stop current playback so pipe_autostart can pick up new audio. */
    suspend fun stopPlayer(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder().url("$baseUrl/api/player/pause").put("{}".toRequestBody(JSON)).build()
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                }
            }
        }

    /** Un-pause whatever OwnTone last had — the other half of [stopPlayer] for play/pause. */
    suspend fun resumePlayer(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder().url("$baseUrl/api/player/play").put("{}".toRequestBody(JSON)).build()
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                }
            }
        }
}
