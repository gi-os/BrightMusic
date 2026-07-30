package com.lightphone.spotify.data

import com.lightphone.spotify.data.local.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Playlist row covers.
 *
 * These have been wrong twice. First `art_url` was hardcoded null; then it was populated correctly but
 * the rows come from the native spclient rootlist, which carries no mosaic for a playlist with no
 * uploaded image — so your own private playlists still had nothing. The list is enriched from the Web
 * API now, and these pin down the selection either way.
 */
class PlaylistArtTest {

    private fun playlist(vararg images: SpotifyImage) = SpotifyPlaylistSimple(
        id = "p1",
        name = "Mix",
        uri = "spotify:playlist:p1",
        images = if (images.isEmpty()) null else images.toList(),
        owner = SpotifyPlaylistOwner(id = "gio", displayName = "Gio"),
    )

    private fun image(url: String, width: Int?) = SpotifyImage(url = url, width = width)

    @Test
    fun `skips the tiny rung a 50dp row would upscale`() {
        // Spotify's playlist ladder is 640 / 300 / 60. A 60px source in a 50dp box on a ~3x panel is
        // upscaled past two to one, which is what made the covers look soft.
        val entity = playlist(
            image("big", 640),
            image("mid", 300),
            image("tiny", 60),
        ).toEntity(sortIndex = 0)

        assertEquals("mid", entity.art_url)
    }

    @Test
    fun `falls back to the widest when everything is small`() {
        val entity = playlist(image("small", 60), image("smaller", 30)).toEntity(sortIndex = 0)

        assertEquals("small", entity.art_url)
    }

    @Test
    fun `an unknown width counts as big enough`() {
        // A missing width is Spotify being terse, not an admission the image is tiny.
        val entity = playlist(image("unsized", null)).toEntity(sortIndex = 0)

        assertEquals("unsized", entity.art_url)
    }

    @Test
    fun `no images means no cover, not a blank url`() {
        assertNull(playlist().toEntity(sortIndex = 0).art_url)
        assertNull(playlist(image("", 300)).toEntity(sortIndex = 0).art_url)
    }

    @Test
    fun `a blank url does not win on width alone`() {
        // The old selection took the smallest and only then checked for blankness, so a zero-width
        // blank entry would win the comparison and null the whole thing out.
        val entity = playlist(image("", 1), image("real", 300)).toEntity(sortIndex = 0)

        assertEquals("real", entity.art_url)
    }

    @Test
    fun `the rest of the row still maps`() {
        val entity = playlist(image("mid", 300)).toEntity(sortIndex = 7)

        assertEquals("p1", entity.playlist_id)
        assertEquals("Mix", entity.name)
        assertEquals("gio", entity.owner_id)
        assertEquals("Gio", entity.owner_name)
        assertEquals(7, entity.sort_index)
    }

    @Test
    fun `search results show playlist art instead of discarding it`() {
        val item = SearchResultItem.Playlist(playlist(image("big", 640), image("mid", 300)))

        assertEquals("mid", item.imageUrl)
    }

    @Test
    fun `a search result with no images has no art`() {
        assertNull(SearchResultItem.Playlist(playlist()).imageUrl)
    }
}
