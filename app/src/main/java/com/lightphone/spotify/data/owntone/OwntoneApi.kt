package com.lightphone.spotify.data.owntone

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

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

// --- Client (all network calls on IO dispatcher) ---

class OwntoneApi(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) {

    private suspend fun <T> io(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.IO, block)

    suspend fun listOutputs(): Result<List<OwntoneOutput>> = runCatching {
        io {
            val response = client.newCall(
                Request.Builder().url("$baseUrl/api/outputs").build()
            ).execute()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response")
            owntoneJson.decodeFromString<OwntoneOutputsResponse>(body).outputs
        }
    }

    suspend fun setOutputSelected(outputId: String, selected: Boolean): Result<Unit> = runCatching {
        io {
            val body = owntoneJson.encodeToString(OwntoneOutputUpdate.serializer(), OwntoneOutputUpdate(selected))
            val response = client.newCall(
                Request.Builder()
                    .url("$baseUrl/api/outputs/$outputId")
                    .put(body.toRequestBody(JSON))
                    .build()
            ).execute()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    suspend fun getPlayerState(): Result<OwntonePlayerState> = runCatching {
        io {
            val response = client.newCall(
                Request.Builder().url("$baseUrl/api/player").build()
            ).execute()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response")
            owntoneJson.decodeFromString<OwntonePlayerState>(body)
        }
    }

    suspend fun setVolume(volume: Int, outputId: String? = null): Result<Unit> = runCatching {
        io {
            val url = if (outputId != null) {
                "$baseUrl/api/player/volume?volume=$volume&output_id=$outputId"
            } else {
                "$baseUrl/api/player/volume?volume=$volume"
            }
            client.newCall(Request.Builder().url(url).put("".toRequestBody(JSON)).build()).execute()
        }
    }

    /** Play a URL directly (radio stream, podcast, etc). Clears queue and starts playback immediately. */
    suspend fun playUrl(url: String): Result<Unit> = runCatching {
        io {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            val apiUrl = "$baseUrl/api/queue/items/add?clear=true&playback=start&uris=$encoded"
            val response = client.newCall(Request.Builder().url(apiUrl).post("".toRequestBody(JSON)).build()).execute()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    companion object {
        suspend fun testConnection(baseUrl: String, client: OkHttpClient = OkHttpClient()): Result<Boolean> = runCatching {
            withContext(Dispatchers.IO) {
                client.newCall(Request.Builder().url("$baseUrl/api/player").build()).execute().isSuccessful
            }
        }
    }
}
