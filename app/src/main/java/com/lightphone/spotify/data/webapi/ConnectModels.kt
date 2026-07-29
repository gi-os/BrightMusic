package com.lightphone.spotify.data.webapi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Spotify Connect wire models for `GET /me/player/devices` and `GET /me/player`.
 *
 * Only the fields LightPhono actually renders or acts on are declared; the client
 * decodes with `ignoreUnknownKeys`, so Spotify is free to keep adding to these.
 */
@Serializable
data class SpotifyDevice(
    /** Null for a device Spotify knows about but cannot currently be targeted. */
    val id: String? = null,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("is_private_session") val isPrivateSession: Boolean = false,
    /** True when the device refuses remote control — transfer will fail with 403. */
    @SerialName("is_restricted") val isRestricted: Boolean = false,
    val name: String = "",
    /** "computer", "smartphone", "speaker", "tv", "avr", "stb", "game_console", … */
    val type: String = "",
    @SerialName("volume_percent") val volumePercent: Int? = null,
    @SerialName("supports_volume") val supportsVolume: Boolean = true,
) {
    /** Targetable devices need an id and must not be restricted. */
    val isTransferable: Boolean get() = !id.isNullOrBlank() && !isRestricted
}

@Serializable
data class SpotifyDevicesResponse(
    val devices: List<SpotifyDevice> = emptyList(),
)

/**
 * Trimmed `GET /me/player`. Spotify returns 204 with no body when nothing is playing
 * anywhere, which [SpotifyWebApi.playerState] maps to null rather than to an empty
 * object — "no session" and "paused session" need to look different to the UI.
 */
@Serializable
data class SpotifyPlayerState(
    val device: SpotifyDevice? = null,
    @SerialName("repeat_state") val repeatState: String? = null,
    @SerialName("shuffle_state") val shuffleState: Boolean = false,
    @SerialName("is_playing") val isPlaying: Boolean = false,
    @SerialName("progress_ms") val progressMs: Long? = null,
    val item: SpotifyPlayerTrack? = null,
)

@Serializable
data class SpotifyPlayerTrack(
    val id: String? = null,
    val uri: String = "",
    val name: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    val artists: List<SpotifyPlayerArtist> = emptyList(),
    val album: SpotifyPlayerAlbum? = null,
)

@Serializable
data class SpotifyPlayerArtist(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class SpotifyPlayerAlbum(
    val id: String = "",
    val name: String = "",
    val images: List<SpotifyPlayerImage> = emptyList(),
)

@Serializable
data class SpotifyPlayerImage(
    val url: String = "",
    val width: Int? = null,
    val height: Int? = null,
)
