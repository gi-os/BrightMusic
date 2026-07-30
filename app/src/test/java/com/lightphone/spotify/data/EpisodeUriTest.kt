package com.lightphone.spotify.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `isEpisodeUri` decides which transport an item gets — ±15 seconds instead of track skip, in the
 * player, on the lock screen and over Bluetooth.
 *
 * It replaced the same string literal written out in four files. These exist so that if the prefix ever
 * changes, one test fails rather than four call sites silently disagreeing.
 */
class EpisodeUriTest {

    @Test
    fun `episode uris are episodes`() {
        assertTrue("spotify:episode:512ojhOuo1ktJprKbVcKyQ".isEpisodeUri())
        assertTrue(EPISODE_URI_PREFIX.isEpisodeUri())
    }

    @Test
    fun `tracks, albums, playlists and shows are not`() {
        assertFalse("spotify:track:6rqhFgbbKwnb9MLmUQDhG6".isEpisodeUri())
        assertFalse("spotify:album:1234".isEpisodeUri())
        assertFalse("spotify:playlist:1234".isEpisodeUri())
        // A show is the container, not a playable episode — it must not take the episode transport.
        assertFalse("spotify:show:1234".isEpisodeUri())
    }

    @Test
    fun `null and blank are not episodes`() {
        assertFalse(null.isEpisodeUri())
        assertFalse("".isEpisodeUri())
    }

    @Test
    fun `a radio stream is not an episode`() {
        // Radio overlays its own uri scheme onto the playback state and must keep its own controls.
        assertFalse("nts:1".isEpisodeUri())
    }

    @Test
    fun `the prefix is not matched mid-string`() {
        assertFalse("https://open.spotify.com/episode/1234".isEpisodeUri())
    }
}
