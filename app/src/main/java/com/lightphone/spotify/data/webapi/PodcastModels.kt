package com.lightphone.spotify.data.webapi

import com.lightphone.spotify.data.SpotifyImage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shows and episodes from the Web API.
 *
 * Only the fields LightPhono renders or acts on; the client decodes with `ignoreUnknownKeys`.
 *
 * Note there is deliberately **no Room entity** for any of this. Podcasts need nothing new on disk:
 * browsing is online, and everything that has to survive a journey — the audio, the episode name, the
 * cover — is already carried by the existing `downloaded_tracks` / `downloaded_collections` tables.
 * Adding entities would mean bumping the Room version, and `PhonoDatabase` uses
 * `fallbackToDestructiveMigration()`, so that would wipe the user's offline music. Settings, resume
 * points and last-seen episode ids are small and non-relational, so they live in preferences.
 */
@Serializable
data class SpotifyShow(
    val id: String = "",
    val uri: String = "",
    val name: String = "",
    val publisher: String = "",
    val description: String = "",
    val images: List<SpotifyImage> = emptyList(),
    @SerialName("total_episodes") val totalEpisodes: Int = 0,
) {
    /** Smallest image: these render at 50dp in a list, and Spotify orders them widest-first. */
    val listArtUrl: String?
        get() = images.minByOrNull { it.width ?: Int.MAX_VALUE }?.url?.takeIf { it.isNotBlank() }

    val detailArtUrl: String?
        get() = images.firstOrNull()?.url?.takeIf { it.isNotBlank() }
}

@Serializable
data class SpotifySavedShow(
    @SerialName("added_at") val addedAt: String? = null,
    val show: SpotifyShow? = null,
)

@Serializable
data class SpotifyEpisode(
    val id: String = "",
    val uri: String = "",
    val name: String = "",
    val description: String = "",
    @SerialName("duration_ms") val durationMs: Long = 0,
    /** `YYYY-MM-DD`, or coarser — see [releasePrecision]. */
    @SerialName("release_date") val releaseDate: String = "",
    @SerialName("release_date_precision") val releasePrecision: String = "day",
    val images: List<SpotifyImage> = emptyList(),
    /**
     * False when Spotify cannot stream it to this account or market. Those episodes are shown greyed
     * rather than hidden, so a gap in a feed is explained rather than mysterious.
     */
    @SerialName("is_playable") val isPlayable: Boolean = true,
    /**
     * Spotify's own resume point. Only populated with the `user-read-playback-position` scope, which
     * this fork does not request — LightPhono keeps positions locally instead, so they also work
     * offline. Parsed anyway in case the scope is ever added.
     */
    @SerialName("resume_point") val resumePoint: SpotifyResumePoint? = null,
) {
    val artUrl: String?
        get() = images.minByOrNull { it.width ?: Int.MAX_VALUE }?.url?.takeIf { it.isNotBlank() }
}

@Serializable
data class SpotifyResumePoint(
    @SerialName("fully_played") val fullyPlayed: Boolean = false,
    @SerialName("resume_position_ms") val resumePositionMs: Long = 0,
)
