package com.lightphone.spotify.data.webapi

import com.lightphone.spotify.data.SpotifyImage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /playlists/{id}?fields=images`, which is what gives the playlist detail header its cover.
 *
 * The native detail carries a cover only for playlists someone uploaded an image to, so for everything
 * else this response is the only source. `fields=` means the payload is images and nothing else, which
 * is worth pinning down: a stricter model would have thrown on the fuller response and a looser one
 * would have hidden a rename.
 */
class PlaylistImagesResponseTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `parses a fields-restricted response`() {
        val body = """
            {"images":[
              {"url":"https://mosaic.scdn.co/640/abc","width":640,"height":640},
              {"url":"https://mosaic.scdn.co/300/abc","width":300,"height":300}
            ]}
        """.trimIndent()

        val parsed = json.decodeFromString<PlaylistImagesResponse>(body)

        assertEquals(2, parsed.images?.size)
        assertEquals("https://mosaic.scdn.co/640/abc", parsed.images?.first()?.url)
    }

    @Test
    fun `a mosaic with no declared dimensions still parses`() {
        // Spotify omits width and height on generated mosaics often enough to matter.
        val parsed = json.decodeFromString<PlaylistImagesResponse>(
            """{"images":[{"url":"https://mosaic.scdn.co/abc"}]}""",
        )

        assertEquals("https://mosaic.scdn.co/abc", parsed.images?.first()?.url)
        assertNull(parsed.images?.first()?.width)
    }

    @Test
    fun `an empty or absent images array is not an error`() {
        assertTrue(json.decodeFromString<PlaylistImagesResponse>("""{"images":[]}""").images!!.isEmpty())
        assertNull(json.decodeFromString<PlaylistImagesResponse>("""{}""").images)
        // Spotify sends an explicit null here for some playlists; coerceInputValues keeps it from
        // throwing, which is why the field is declared nullable.
        assertNull(json.decodeFromString<PlaylistImagesResponse>("""{"images":null}""").images)
    }

    @Test
    fun `extra keys are tolerated if fields is ever dropped`() {
        val parsed = json.decodeFromString<PlaylistImagesResponse>(
            """{"name":"Mix","snapshot_id":"x","images":[{"url":"u","width":300}],"tracks":{"total":9}}""",
        )

        assertEquals("u", parsed.images?.first()?.url)
    }

    @Test
    fun `the header takes the widest image`() {
        // The header is a large box, unlike the 50dp list rows, so this is the one place that wants the
        // biggest rung rather than the smallest usable one.
        val images = listOf(
            SpotifyImage(url = "small", width = 60),
            SpotifyImage(url = "huge", width = 640),
            SpotifyImage(url = "mid", width = 300),
        )

        assertEquals("huge", images.widestArtUrl())
    }

    @Test
    fun `a blank url never wins the widest comparison`() {
        val images = listOf(SpotifyImage(url = "", width = 640), SpotifyImage(url = "real", width = 300))

        assertEquals("real", images.widestArtUrl())
    }

    @Test
    fun `no usable image gives nothing rather than a blank url`() {
        assertNull(emptyList<SpotifyImage>().widestArtUrl())
        assertNull(listOf(SpotifyImage(url = "", width = 640)).widestArtUrl())
    }
}
