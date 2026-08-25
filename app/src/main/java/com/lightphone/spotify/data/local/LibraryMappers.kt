package com.lightphone.spotify.data.local

import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.SpotifySavedAlbum
import com.lightphone.spotify.data.SpotifySavedTrack
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.data.webapi.LIST_ART_MIN_WIDTH
import com.lightphone.spotify.data.webapi.artUrlAtLeast

/**
 * A saved-track row, or null when there is no track to make one out of.
 *
 * `track` is documented nullable on [SpotifySavedTrack] and means it: Spotify returns a saved item
 * with a null track when it is not available in the user's market. This read it as `track!!`, so one
 * region-locked save in a page of fifty threw out of the mapper, out of `insertPage`, and out of the
 * whole sync — the library then stopped at that page and never finished, on every retry, because the
 * offending row is at the same offset every time.
 */
fun SpotifySavedTrack.toEntity(sortIndex: Int): LikedTrackEntity? {
    val meta = track?.toMetadata() ?: return null
    return LikedTrackEntity(
        uri = meta.uri,
        title = meta.title,
        artists = meta.artists,
        album_name = meta.album,
        duration_ms = meta.durationMs,
        art_url = meta.artUrl,
        album_id = meta.albumId,
        added_at = addedAt,
        sort_index = sortIndex,
    )
}

/** As above, for a saved album whose `album` is null because it is unavailable in this market. */
fun SpotifySavedAlbum.toEntity(sortIndex: Int): SavedAlbumEntity? {
    val album = album ?: return null
    return SavedAlbumEntity(
        album_id = album.id,
        uri = album.uri.ifBlank { "spotify:album:${album.id}" },
        name = album.name,
        artist_names = album.artists.joinToString(" · ") { it.name },
        art_url = album.images.firstOrNull()?.url,
        added_at = addedAt,
        sort_index = sortIndex,
    )
}

fun SpotifyPlaylistSimple.toEntity(sortIndex: Int): PlaylistEntity =
    PlaylistEntity(
        playlist_id = id,
        uri = uri.ifBlank { "spotify:playlist:$id" },
        name = name,
        owner_id = owner?.id.orEmpty(),
        owner_name = owner?.let { o ->
            o.displayName?.takeIf { it.isNotBlank() && it != o.id }
        }.orEmpty(),
        // Was hardcoded null, which is why playlist rows had no cover even though the field and the
        // API response both had one all along. Sized the same way podcast rows are: the smallest rung
        // that is still at least 240px, because a 50dp box on this ~3x panel upscales a 64px square
        // past two to one — soft, and with almost nothing for the dither treatment to work with.
        art_url = images.orEmpty().artUrlAtLeast(LIST_ART_MIN_WIDTH),
        track_count = trackCount,
        snapshot_id = snapshotId,
        is_public = public ?: false,
        is_collaborative = collaborative,
        sort_index = sortIndex,
    )

fun TrackMetadata.toLikedTrackEntity(sortIndex: Int, addedAt: String? = null): LikedTrackEntity =
    LikedTrackEntity(
        uri = uri,
        title = title,
        artists = artists,
        album_name = album,
        duration_ms = durationMs,
        art_url = artUrl,
        album_id = albumId,
        added_at = addedAt,
        sort_index = sortIndex,
    )
