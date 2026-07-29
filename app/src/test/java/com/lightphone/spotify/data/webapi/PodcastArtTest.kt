package com.lightphone.spotify.data.webapi

import com.lightphone.spotify.data.SpotifyImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PodcastArtTest {

    /** Spotify's actual ladder for shows and episodes, widest first. */
    private val ladder = listOf(
        SpotifyImage(url = "big", width = 640, height = 640),
        SpotifyImage(url = "mid", width = 300, height = 300),
        SpotifyImage(url = "tiny", width = 64, height = 64),
    )

    @Test
    fun listThumbnailSkipsThe64pxOne() {
        val show = SpotifyShow(images = ladder)
        assertEquals("mid", show.listArtUrl)
        assertEquals("mid", SpotifyEpisode(images = ladder).artUrl)
    }

    @Test
    fun coversTakeTheWidest() {
        assertEquals("big", SpotifyShow(images = ladder).detailArtUrl)
        assertEquals("big", SpotifyEpisode(images = ladder).fullArtUrl)
    }

    @Test
    fun whenNothingIsBigEnoughTheBiggestWins() {
        val small = listOf(
            SpotifyImage(url = "tiny", width = 64),
            SpotifyImage(url = "smaller", width = 32),
        )
        assertEquals("tiny", SpotifyShow(images = small).listArtUrl)
    }

    @Test
    fun unknownWidthsAreAssumedUsable() {
        val unknown = listOf(SpotifyImage(url = "who-knows"))
        assertEquals("who-knows", SpotifyShow(images = unknown).listArtUrl)
        assertEquals("who-knows", SpotifyShow(images = unknown).detailArtUrl)
    }

    @Test
    fun blankAndEmptyGiveNothing() {
        assertNull(SpotifyShow(images = emptyList()).listArtUrl)
        assertNull(SpotifyShow(images = listOf(SpotifyImage(url = "", width = 640))).listArtUrl)
        assertNull(SpotifyEpisode(images = emptyList()).fullArtUrl)
    }
}
