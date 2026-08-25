package com.lightphone.spotify.data

import com.lightphone.spotify.data.local.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A saved page with an unavailable item in it.
 *
 * `SpotifySavedTrack.track` and `SpotifySavedAlbum.album` are documented nullable and mean it:
 * Spotify returns the saved row with a null payload when the item is not available in the user's
 * market. Both mappers used to read them as `!!`, so one region-locked save threw out of the mapper,
 * out of `insertPage`, and out of the library sync — which then stopped at that page and never got
 * past it, on every retry, because the offending row sits at the same offset every time.
 *
 * The index half is checked as well as the null half. `sort_index` is the row's absolute position in
 * the remote library (page offset + position in page) and is the only thing Liked Songs is ordered
 * by, so dropping a row must leave a hole rather than renumber the rows after it — the next page
 * still starts at its own offset, and renumbering would make the two overlap.
 */
class SavedItemNullFieldTest {

    private fun track(id: String) = SpotifyTrack(
        id = id,
        name = "Track $id",
        uri = "spotify:track:$id",
        artists = listOf(SpotifyArtist(id = "a1", name = "Artist")),
        album = SpotifyAlbumSimple(id = "al1", name = "Album", uri = "spotify:album:al1"),
        durationMs = 180_000,
    )

    private fun album(id: String) = SpotifyAlbumSimple(
        id = id,
        name = "Album $id",
        uri = "spotify:album:$id",
        artists = listOf(SpotifyArtist(id = "a1", name = "Artist")),
    )

    @Test
    fun `a saved track with no track maps to null instead of throwing`() {
        assertNull(SpotifySavedTrack(addedAt = "2026-01-01T00:00:00Z", track = null).toEntity(0))
    }

    @Test
    fun `a saved album with no album maps to null instead of throwing`() {
        assertNull(SpotifySavedAlbum(addedAt = "2026-01-01T00:00:00Z", album = null).toEntity(0))
    }

    @Test
    fun `a track page with a hole in it keeps the remaining absolute sort indices`() {
        val startSortIndex = 50
        val page = listOf(
            SpotifySavedTrack(addedAt = "2026-01-03T00:00:00Z", track = track("t1")),
            SpotifySavedTrack(addedAt = "2026-01-02T00:00:00Z", track = null),
            SpotifySavedTrack(addedAt = "2026-01-01T00:00:00Z", track = track("t3")),
        )

        // The expression LikedTracksSync.insertPage runs.
        val entities = page.mapIndexedNotNull { index, saved ->
            saved.toEntity(sortIndex = startSortIndex + index)
        }

        assertEquals(2, entities.size)
        assertEquals("spotify:track:t1", entities[0].uri)
        assertEquals(50, entities[0].sort_index)
        // 52, not 51: the unavailable row leaves its position empty rather than pulling this one up
        // into it, so the next page starting at 100 cannot collide with it.
        assertEquals("spotify:track:t3", entities[1].uri)
        assertEquals(52, entities[1].sort_index)
    }

    @Test
    fun `an album page with a hole in it keeps the remaining absolute sort indices`() {
        val startSortIndex = 50
        val page = listOf(
            SpotifySavedAlbum(addedAt = "2026-01-03T00:00:00Z", album = album("al1")),
            SpotifySavedAlbum(addedAt = "2026-01-02T00:00:00Z", album = null),
            SpotifySavedAlbum(addedAt = "2026-01-01T00:00:00Z", album = album("al3")),
        )

        val entities = page.mapIndexedNotNull { index, saved ->
            saved.toEntity(sortIndex = startSortIndex + index)
        }

        assertEquals(2, entities.size)
        assertEquals("al1", entities[0].album_id)
        assertEquals(50, entities[0].sort_index)
        assertEquals("al3", entities[1].album_id)
        assertEquals(52, entities[1].sort_index)
    }
}
