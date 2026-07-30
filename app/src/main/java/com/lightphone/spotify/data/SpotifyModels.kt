package com.lightphone.spotify.data

import com.lightphone.spotify.data.webapi.LIST_ART_MIN_WIDTH
import com.lightphone.spotify.data.webapi.artUrlAtLeast
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpotifyImage(
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class SpotifyArtist(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val popularity: Int = 0,
)

@Serializable
data class SpotifyAlbumSimple(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    @SerialName("album_type") val albumType: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val popularity: Int = 0,
)

@Serializable
data class SpotifyTrack(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val artists: List<SpotifyArtist> = emptyList(),
    val album: SpotifyAlbumSimple? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("track_number") val trackNumber: Int = 0,
    @SerialName("disc_number") val discNumber: Int = 1,
    val popularity: Int = 0,
)

@Serializable
data class SpotifySavedAlbum(
    @SerialName("added_at") val addedAt: String? = null,
    /** Null when the album is unavailable in the user's market. */
    val album: SpotifyAlbumSimple? = null,
)

@Serializable
data class SpotifySavedTrack(
    @SerialName("added_at") val addedAt: String? = null,
    /** Null when the track is unavailable in the user's market. */
    val track: SpotifyTrack? = null,
)

@Serializable
data class PagedResponse<T>(
    val items: List<T> = emptyList(),
    val next: String? = null,
    val total: Int = 0,
)

/** Search API pages may contain null slots for unavailable results. */
@Serializable
data class SearchPagedResponse<T>(
    val items: List<T?> = emptyList(),
    val next: String? = null,
    val total: Int = 0,
)

@Serializable
data class SpotifyAlbumDetail(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val artists: List<SpotifyArtist> = emptyList(),
    val tracks: PagedResponse<SpotifyTrack> = PagedResponse(),
)

@Serializable
data class SpotifyArtistDetail(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage> = emptyList(),
    val genres: List<String> = emptyList(),
    val followers: Followers? = null,
)

@Serializable
data class Followers(
    val total: Int = 0,
)

@Serializable
data class TopTracksResponse(
    val tracks: List<SpotifyTrack> = emptyList(),
)

@Serializable
data class SpotifySearchResults(
    val tracks: SearchPagedResponse<SpotifyTrack>? = null,
    val albums: SearchPagedResponse<SpotifyAlbumSimple>? = null,
    val artists: SearchPagedResponse<SpotifyArtist>? = null,
    val playlists: SearchPagedResponse<SpotifyPlaylistSimple>? = null,
    val shows: SearchPagedResponse<com.lightphone.spotify.data.webapi.SpotifyShow>? = null,
)

@Serializable
data class SpotifyPlaylistTracksRef(
    val total: Int = 0,
)

@Serializable
data class SpotifyPlaylistSimple(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage>? = null,
    val owner: SpotifyPlaylistOwner? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val tracks: SpotifyPlaylistTracksRef? = null,
    val public: Boolean? = null,
    val collaborative: Boolean = false,
    val description: String? = null,
    /** Not documented on GET /me/playlists; parsed when Spotify returns it. */
    @SerialName("added_at") val addedAt: String? = null,
) {
    val trackCount: Int get() = tracks?.total ?: 0
}

@Serializable
data class SpotifyPlaylistDetail(
    val id: String = "",
    val name: String = "",
    val uri: String = "",
    val images: List<SpotifyImage>? = null,
    val owner: SpotifyPlaylistOwner? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
    val tracks: SpotifyPlaylistTracksRef? = null,
    val public: Boolean? = null,
    val collaborative: Boolean = false,
    val description: String? = null,
) {
    val trackCount: Int get() = tracks?.total ?: 0
}

fun SpotifyPlaylistDetail.toPlaylistSimple(): SpotifyPlaylistSimple =
    SpotifyPlaylistSimple(
        id = id,
        name = name,
        uri = uri.ifBlank { "spotify:playlist:$id" },
        images = images,
        owner = owner,
        snapshotId = snapshotId,
        tracks = tracks,
        public = public,
        collaborative = collaborative,
        description = description,
    )

@Serializable
data class SpotifyPlaylistOwner(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SpotifyPlaylistTrackItem(
    @SerialName("added_at") val addedAt: String? = null,
    val track: SpotifyTrack? = null,
)

@Serializable
data class SpotifyPublicUser(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class SpotifyCurrentUser(
    val id: String = "",
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
data class CreatePlaylistBody(
    val name: String,
    val public: Boolean = false,
    val description: String? = null,
    val collaborative: Boolean = false,
)

@Serializable
data class ChangePlaylistDetailsBody(
    val name: String? = null,
    val public: Boolean? = null,
    val description: String? = null,
    val collaborative: Boolean? = null,
)

@Serializable
data class AddPlaylistItemsBody(
    val uris: List<String>,
    val position: Int? = null,
    @SerialName("snapshot_id") val snapshotId: String? = null,
)

@Serializable
data class RemovePlaylistTrackRef(
    val uri: String,
)

@Serializable
data class RemovePlaylistItemsBody(
    val tracks: List<RemovePlaylistTrackRef>,
    @SerialName("snapshot_id") val snapshotId: String? = null,
)

@Serializable
data class SnapshotResponse(
    @SerialName("snapshot_id") val snapshotId: String = "",
)

@Serializable
data class ReorderPlaylistItemsBody(
    @SerialName("range_start") val rangeStart: Int,
    @SerialName("insert_before") val insertBefore: Int,
    @SerialName("range_length") val rangeLength: Int = 1,
    @SerialName("snapshot_id") val snapshotId: String? = null,
)

/** Prefix of a Spotify episode uri. */
const val EPISODE_URI_PREFIX = "spotify:episode:"

/**
 * Whether this uri is a podcast episode.
 *
 * Episodes get a different transport everywhere — ±15 seconds rather than track skip, in the player, on
 * the lock screen and over Bluetooth — and the check was four copies of the same string literal across
 * four files that all had to agree.
 */
fun String?.isEpisodeUri(): Boolean = this?.startsWith(EPISODE_URI_PREFIX) == true

sealed class SearchResultItem {
    abstract val id: String
    abstract val title: String
    abstract val subtitle: String
    abstract val imageUrl: String?
    abstract val uri: String

    data class Track(val track: SpotifyTrack) : SearchResultItem() {
        override val id = track.id
        override val title = track.name
        override val subtitle = "Song • ${track.artists.joinToString { it.name }}"
        override val imageUrl = track.album?.images?.firstOrNull()?.url
        override val uri = track.uri
    }

    data class Album(val album: SpotifyAlbumSimple) : SearchResultItem() {
        override val id = album.id
        override val title = album.name
        override val subtitle = "Album • ${album.artists.joinToString { it.name }}"
        override val imageUrl = album.images.firstOrNull()?.url
        override val uri = album.uri
    }

    data class Artist(val artist: SpotifyArtist) : SearchResultItem() {
        override val id = artist.id
        override val title = artist.name
        override val subtitle = "Artist"
        override val imageUrl = artist.images.firstOrNull()?.url
        override val uri = artist.uri
    }

    data class Playlist(val playlist: SpotifyPlaylistSimple) : SearchResultItem() {
        override val id = playlist.id
        override val title = playlist.name
        override val subtitle = "Playlist • ${playlist.owner?.displayName ?: "Playlist"}"

        // Was hardcoded null, discarding art the search response does carry — unlike the rootlist,
        // /search returns the mosaic.
        override val imageUrl = playlist.images.orEmpty().artUrlAtLeast(LIST_ART_MIN_WIDTH)
        override val uri = playlist.uri
    }

    /**
     * A podcast show.
     *
     * Shows, not episodes. An episode result would have nowhere useful to go — you would tap it and
     * either play something mid-series or land on a screen for one item — whereas a show opens the
     * episode list, which is where you decide what to listen to and whether to auto-download it.
     */
    data class Show(val show: com.lightphone.spotify.data.webapi.SpotifyShow) : SearchResultItem() {
        override val id = show.id
        override val title = show.name
        override val subtitle = "Podcast • ${show.publisher}".removeSuffix(" • ")
        override val imageUrl = show.listArtUrl
        override val uri = show.uri
    }
}

enum class SearchFilter(val label: String) {
    All("All"),
    Tracks("Songs"),
    Artists("Artists"),
    Albums("Albums"),
    Playlists("Playlists"),
    Shows("Podcasts"),
}

enum class PlaylistFilter(val label: String) {
    YourPlaylists("By You"),
    All("All"),
}

/**
 * Which half of the Liked tab is showing.
 *
 * Songs and albums used to be two tabs. They are one tab with a switch now, mirroring
 * [PlaylistFilter]: they are the same idea — things you saved — and the bar only has room for six.
 */
enum class LikedFilter(val label: String) {
    Songs("Songs"),
    Albums("Albums"),
}

data class SearchResults(
    val query: String,
    val artists: List<SpotifyArtist> = emptyList(),
    val albums: List<SpotifyAlbumSimple> = emptyList(),
    val tracks: List<SpotifyTrack> = emptyList(),
    val playlists: List<SpotifyPlaylistSimple> = emptyList(),
    val shows: List<com.lightphone.spotify.data.webapi.SpotifyShow> = emptyList(),
    val topResult: SearchResultItem? = null,
    val rankedItems: List<SearchResultItem> = emptyList(),
) {
    fun isEmpty(): Boolean =
        topResult == null &&
            rankedItems.isEmpty() &&
            artists.isEmpty() &&
            albums.isEmpty() &&
            tracks.isEmpty() &&
            playlists.isEmpty() &&
            shows.isEmpty()

    /** Top result + interleaved remainder for the All filter. */
    fun allItems(): List<SearchResultItem> = buildList {
        topResult?.let { add(it) }
        addAll(rankedItems)
    }

    fun itemsForFilter(filter: SearchFilter): Pair<SearchResultItem?, List<SearchResultItem>> =
        when (filter) {
            SearchFilter.All -> topResult to rankedItems
            SearchFilter.Tracks -> null to tracks.take(10).map { SearchResultItem.Track(it) }
            SearchFilter.Artists -> null to artists.take(10).map { SearchResultItem.Artist(it) }
            SearchFilter.Albums -> null to albums.take(10).map { SearchResultItem.Album(it) }
            SearchFilter.Playlists -> null to playlists.take(10).map { SearchResultItem.Playlist(it) }
            SearchFilter.Shows -> null to shows.take(10).map { SearchResultItem.Show(it) }
        }
}

fun SpotifyTrack.toMetadata(): TrackMetadata = TrackMetadata(
    uri = uri,
    title = name,
    artists = artists.joinToString { it.name },
    album = album?.name ?: "",
    durationMs = durationMs,
    artUrl = album?.images?.firstOrNull()?.url,
    albumId = album?.id,
    artistIds = artists.map { it.id },
)
