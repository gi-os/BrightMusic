package com.lightphone.spotify.data.owntone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val PAYLOAD_TYPE_OWNTONE = "owntone"
private const val PAYLOAD_TYPE_MA = "music_assistant"
private const val PAYLOAD_VERSION = 1

@Serializable
data class BridgeQrPayload(
    @SerialName("type") val type: String,
    @SerialName("url") val url: String,
    @SerialName("name") val name: String = "",
    @SerialName("token") val token: String = "",
)

private val bridgeQrJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

fun parseBridgeQrPayload(raw: String): Result<BridgeQrPayload> = runCatching {
    val payload = bridgeQrJson.decodeFromString<BridgeQrPayload>(raw.trim())
    require(payload.type == PAYLOAD_TYPE_OWNTONE || payload.type == PAYLOAD_TYPE_MA) {
        "Not a BrightMusic speaker bridge QR code. Expected type 'owntone' or 'music_assistant', got '${payload.type}'."
    }
    val url = payload.url.trim().trimEnd('/')
    require(url.isNotEmpty()) { "Bridge URL is empty." }
    require(url.startsWith("http://") || url.startsWith("https://")) {
        "Bridge URL must start with http:// or https://"
    }
    payload.copy(url = url)
}
