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
    /** The list thumbnail. See [artUrlAtLeast] for why this is not simply the smallest. */
    val listArtUrl: String?
        get() = images.artUrlAtLeast(LIST_ART_MIN_WIDTH)

    val detailArtUrl: String?
        get() = images.widestArtUrl()
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
    /** The list thumbnail. */
    val artUrl: String?
        get() = images.artUrlAtLeast(LIST_ART_MIN_WIDTH)

    /**
     * The biggest cover there is, for the player and for the copy kept beside a download.
     * Both are looked at full width, so the thumbnail would be a visibly soft square.
     */
    val fullArtUrl: String?
        get() = images.widestArtUrl()
}

/** A list row's artwork box, in dp. The screen is roughly 3x, so ~150px of pixels. */
private const val LIST_ART_MIN_WIDTH = 240

/**
 * Smallest image that is still at least [minWidth] wide, falling back to the widest one
 * available.
 *
 * Picking the genuinely smallest was wrong: Spotify's ladder for shows and episodes is
 * 640 / 300 / 64, and a 64px source in a 50dp box on a 3x panel is upscaled well past two
 * to one — it arrives soft, and the dither treatment then has almost no detail to work
 * with. 300 costs a few KB more and looks like artwork instead of a thumbnail of one.
 */
internal fun List<SpotifyImage>.artUrlAtLeast(minWidth: Int): String? {
    val usable = filter { it.url.isNotBlank() }
    if (usable.isEmpty()) return null
    val bigEnough = usable.filter { (it.width ?: Int.MAX_VALUE) >= minWidth }
    // Unknown widths count as big enough rather than being discarded: a missing width is
    // Spotify being terse, not an admission that the image is tiny.
    return (bigEnough.minByOrNull { it.width ?: Int.MAX_VALUE } ?: usable.maxByOrNull { it.width ?: 0 })?.url
}

internal fun List<SpotifyImage>.widestArtUrl(): String? =
    filter { it.url.isNotBlank() }.maxByOrNull { it.width ?: 0 }?.url

@Serializable
data class SpotifyResumePoint(
    @SerialName("fully_played") val fullyPlayed: Boolean = false,
    @SerialName("resume_position_ms") val resumePositionMs: Long = 0,
)
