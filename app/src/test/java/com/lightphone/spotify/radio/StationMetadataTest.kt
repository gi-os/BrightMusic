package com.lightphone.spotify.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Fixtures are trimmed from real responses captured 2026-08-09. */
class StationMetadataTest {

    private val playlistHtml = """
        <a href="/WNYU/pl/22823235/The-Jukebox-Joint?sp=455890558">12:39 AM</a>
        <a href="http://twitter.com/share?url=x&text=%22you+can+have+Watergate%22+by+The+J.B.%27s+on+WNYU&via=spinitron">t</a>
        <a href="/WNYU/pl/22823235/The-Jukebox-Joint?sp=455890669">12:41 AM</a>
        <a href="http://twitter.com/share?url=x&text=%22have+a+talk+with+god%22+by+Stevie+Wonder+on+WNYU&via=spinitron">t</a>
    """.trimIndent()

    @Test
    fun `latest spin is the last one down the page`() {
        // Spins are listed oldest first, so the newest is last — taking the first would leave the
        // player an hour behind the radio.
        assertEquals("Stevie Wonder - have a talk with god", StationMetadata.latestSpin(playlistHtml)?.text)
    }

    @Test
    fun `an apostrophe survives decoding`() {
        val one = """<a href="...text=%22you+can+have+Watergate%22+by+The+J.B.%27s+on+WNYU&via=spinitron">"""
        assertEquals("The J.B.'s - you can have Watergate", StationMetadata.latestSpin(one)?.text)
    }

    @Test
    fun `a playlist with no spins yet has no answer`() {
        assertNull(StationMetadata.latestSpin("<html>show about to start</html>"))
    }

    @Test
    fun `the spin's own cover is read, upsized, and windowed to its own row`() {
        // Captured shape 2026-08-13: each spin row carries its cover as an absolute Apple
        // Music thumbnail; site glyphs are relative /static/ paths.
        val html = """
            <img src="https://is1-ssl.mzstatic.com/image/thumb/old/mzi.old.jpg/150x150bb.jpg" alt="old">
            <a href="...text=%22you+can+have+Watergate%22+by+The+J.B.%27s+on+WNYU&via=spinitron">t</a>
            <img src="/static/images/loudspeaker.svg" alt="Start archive player">
            <img src="https://is1-ssl.mzstatic.com/image/thumb/Music124/mzi.jbeoybup.jpg/150x150bb.jpg" alt="new">
            <a href="...text=%22have+a+talk+with+god%22+by+Stevie+Wonder+on+WNYU&via=spinitron">t</a>
        """.trimIndent()
        val spin = StationMetadata.latestSpin(html)
        assertEquals("Stevie Wonder - have a talk with god", spin?.text)
        // The newest spin's cover, not the previous song's, asked for at player size.
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music124/mzi.jbeoybup.jpg/600x600bb.jpg",
            spin?.coverUrl,
        )
    }

    @Test
    fun `a spin with no cover has no cover, not somebody else's`() {
        val html = """
            <img src="https://is1-ssl.mzstatic.com/image/thumb/old/mzi.old.jpg/150x150bb.jpg" alt="old">
            <a href="...text=%22you+can+have+Watergate%22+by+The+J.B.%27s+on+WNYU&via=spinitron">t</a>
            <img src="/static/images/noart.svg" alt="no art">
            <a href="...text=%22have+a+talk+with+god%22+by+Stevie+Wonder+on+WNYU&via=spinitron">t</a>
        """.trimIndent()
        assertNull(StationMetadata.latestSpin(html)?.coverUrl)
    }

    @Test
    fun `a non-apple cover passes through untouched`() {
        val html = """
            <img src="https://spinitron.com/images/cover.jpg" alt="c">
            <a href="...text=%22have+a+talk+with+god%22+by+Stevie+Wonder+on+WNYU&via=spinitron">t</a>
        """.trimIndent()
        assertEquals("https://spinitron.com/images/cover.jpg", StationMetadata.latestSpin(html)?.coverUrl)
    }

    @Test
    fun `newest playlist is the highest id, not the first link`() {
        val station = """
            <a href="/WNYU/pl/22821275/The-12-Mix">a</a>
            <a href="/WNYU/pl/22826410/The-New-Afternoon-Show">b</a>
            <a href="/WNYU/pl/22822589/Bosnia-Jeans">c</a>
        """.trimIndent()
        assertEquals("https://spinitron.com/WNYU/pl/22826410", StationMetadata.newestPlaylistUrl(station))
    }

    @Test
    fun `a station page with no playlists yields nothing`() {
        assertNull(StationMetadata.newestPlaylistUrl("<html>WNYU</html>"))
    }

    @Test
    fun `wnyc falls back to the show when no track is playing`() {
        val json = """
            {"has_playlists": false, "current_show": {"title": "On the Media",
             "fullImage": {"url": "https://media.wnyc.org/i/300/300/c/80/1/onthemedia.png", "width": 300},
             "listImage": {"url": "https://media.wnyc.org/i/60/60/c/80/1/onthemedia.png", "width": 60}},
             "current_playlist_item": null}
        """.trimIndent()
        val now = StationMetadata.parseWnyc(json)
        assertEquals("On the Media", now?.text)
        // Widest image: this becomes a player cover, not a list thumbnail.
        assertEquals("https://media.wnyc.org/i/300/300/c/80/1/onthemedia.png", now?.artUrl)
    }

    @Test
    fun `wnyc prefers the playing track and shapes it like an icecast line`() {
        val json = """
            {"current_show": {"title": "New Sounds",
             "fullImage": {"url": "https://media.wnyc.org/show.png"}},
             "current_playlist_item": {"title": "Music for 18 Musicians", "artist": "Steve Reich"}}
        """.trimIndent()
        val now = StationMetadata.parseWnyc(json)
        // Artist - Title, so RadioTrackMatch handles it identically to every other station.
        assertEquals("Steve Reich - Music for 18 Musicians", now?.text)
    }

    @Test
    fun `wnyc with nothing on air says nothing`() {
        assertNull(StationMetadata.parseWnyc("""{"current_show": null, "current_playlist_item": null}"""))
    }

    @Test
    fun `sources are picked from the name or the url`() {
        assertEquals(StationMetadata.Source.SPINITRON_WNYU, StationMetadata.sourceFor("WNYU 89.1", null))
        assertEquals(StationMetadata.Source.WNYC, StationMetadata.sourceFor("WNYC 93.9 FM", null))
        assertEquals(StationMetadata.Source.WNYC, StationMetadata.sourceFor(null, "https://fm939.wnyc.org/wnycfm"))
        assertEquals(StationMetadata.Source.NONE, StationMetadata.sourceFor("NTS 1", "https://stream.nts.live/stream"))
    }
}
