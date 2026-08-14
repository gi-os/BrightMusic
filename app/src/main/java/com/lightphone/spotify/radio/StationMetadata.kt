package com.lightphone.spotify.radio

import java.net.URLDecoder

/**
 * Now-playing for two New York stations that do not put it in their stream.
 *
 * Most stations broadcast `Artist - Title` as Icecast metadata and need none of this. WNYU and
 * WNYC do not: WNYU logs its spins to Spinitron and WNYC publishes a schedule API. Both are
 * therefore special-cased, on purpose — this is not a general "scrape any station" mechanism and
 * should not grow into one.
 *
 * All parsing lives here as pure functions so it can be tested against captured HTML rather than
 * against the internet; see `StationMetadataTest`.
 */
object StationMetadata {

    /** Which extra source, if any, a station's name or stream url implies. */
    enum class Source { NONE, SPINITRON_WNYU, WNYC }

    fun sourceFor(name: String?, url: String?): Source {
        val haystack = "${name.orEmpty()} ${url.orEmpty()}".lowercase()
        return when {
            // WNYU before WNYC: "wnyu" contains none of "wnyc", but a station named
            // "WNYU / WNYC simulcast" should take the one it actually is.
            haystack.contains("wnyu") -> Source.SPINITRON_WNYU
            haystack.contains("wnyc") -> Source.WNYC
            else -> Source.NONE
        }
    }

    // --- WNYU, via Spinitron -------------------------------------------------

    const val SPINITRON_STATION_URL = "https://spinitron.com/WNYU/"

    /** The station's own logo, so it is obvious at a glance that the parse worked. */
    const val WNYU_LOGO = "https://farm4.staticflickr.com/3847/14755756154_e4fdf4f6e2_m.jpg"

    /**
     * The newest playlist linked from the station page.
     *
     * Ids increase over time, so the largest is the most recent — safer than trusting the order
     * of the "Recent" list, which is grouped by show rather than strictly by time.
     */
    fun newestPlaylistUrl(stationHtml: String): String? {
        val ids = PLAYLIST_LINK.findAll(stationHtml)
            .mapNotNull { it.groupValues[1].toLongOrNull() }
            .toList()
        val newest = ids.maxOrNull() ?: return null
        return "https://spinitron.com/WNYU/pl/$newest"
    }

    /** One spin off a playlist page: the line for the label, and its cover for the player. */
    data class Spin(val text: String, val coverUrl: String?)

    /**
     * The last spin on a playlist page, as `Artist - Title` plus its cover art.
     *
     * The text is read out of the Twitter share links rather than the visible markup. The share
     * text is `"Title" by Artist on WNYU` — one unambiguous shape, already separated into two
     * fields, where the rendered row runs the artist, title and album together with no delimiter
     * and would have to be guessed at.
     *
     * The last match, not the first: spins are listed oldest to newest down the page.
     *
     * The cover is the spin row's own `<img>` — Spinitron renders one per spin, usually an Apple
     * Music thumbnail. Only absolute urls count: the "no art" placeholder and the archive-player
     * glyph are site-relative `/static/` paths and must not end up on the player. The search is
     * windowed to the last spin's own stretch of the page so an earlier song's cover can never be
     * hung on the current one.
     */
    fun latestSpin(playlistHtml: String): Spin? {
        val matches = SHARE_TEXT.findAll(playlistHtml).toList()
        val match = matches.lastOrNull() ?: return null
        val title = decode(match.groupValues[1]).trim()
        val artist = decode(match.groupValues[2]).trim()
        if (title.isBlank() || artist.isBlank()) return null
        val windowStart = if (matches.size > 1) matches[matches.size - 2].range.last else 0
        val window = playlistHtml.substring(windowStart, match.range.first)
        val cover = COVER_IMG.findAll(window).lastOrNull()?.groupValues?.get(1)?.let(::upsized)
        return Spin(text = "$artist - $title", coverUrl = cover)
    }

    /**
     * Spinitron thumbnails are 150px and the player cover is full-width; Apple's image CDN bakes
     * the size into the path and serves any other on request, so ask for one that survives the
     * scale-up. Non-Apple covers pass through untouched — their urls promise nothing.
     */
    private fun upsized(url: String): String =
        if (url.contains(".mzstatic.com/")) url.replace(THUMB_SIZE, "/600x600bb.jpg") else url

    private val PLAYLIST_LINK = Regex("""/WNYU/pl/(\d+)""")

    /** `text=%22Title%22+by+Artist+on+WNYU`, url-encoded, inside a twitter share href. */
    private val SHARE_TEXT = Regex("""text=%22(.+?)%22\+by\+(.+?)\+on\+WNYU""")

    /** An absolute image url — site-relative `/static/` glyphs are exactly what this excludes. */
    private val COVER_IMG = Regex("""<img[^>]+src="(https?://[^"]+)"""")

    private val THUMB_SIZE = Regex("""/\d+x\d+bb\.jpg$""")

    private fun decode(encoded: String): String =
        runCatching { URLDecoder.decode(encoded.replace("+", " "), "UTF-8") }.getOrDefault(encoded)

    // --- WNYC, via its own API ----------------------------------------------

    /**
     * WNYC 93.9's schedule endpoint. Public, no key.
     *
     * `current_playlist_item` is populated when music is on and null through the talk shows that
     * make up most of the schedule — so the show's own title and art are the fallback, and are
     * worth showing on their own: "On the Media" is a more useful label than a stream url.
     */
    const val WNYC_WHATS_ON_URL = "https://api.wnyc.org/api/v1/whats_on/wnyc-fm939/"

    data class WnycNowPlaying(val text: String, val artUrl: String?)

    /**
     * Pull the useful part out of the schedule response.
     *
     * Hand-parsed with regexes over the raw body rather than modelled: the response carries
     * roughly forty fields across four nested objects, of which three matter, and a data class
     * for the rest is a maintenance liability against an API this app does not own.
     */
    fun parseWnyc(json: String): WnycNowPlaying? {
        val playlistBlock = objectAfter(json, "\"current_playlist_item\"")
        if (playlistBlock != null) {
            val title = stringField(playlistBlock, "title")
            val artist = stringField(playlistBlock, "artist")
                ?: stringField(playlistBlock, "artist_name")
            if (!title.isNullOrBlank()) {
                // Shaped as `Artist - Title` so RadioTrackMatch can parse it exactly as it does
                // an Icecast line — one format for everything downstream.
                val text = if (artist.isNullOrBlank()) title else "$artist - $title"
                return WnycNowPlaying(text, imageUrl(playlistBlock) ?: showImage(json))
            }
        }
        val show = objectAfter(json, "\"current_show\"") ?: return null
        val title = stringField(show, "title") ?: return null
        return WnycNowPlaying(title, showImage(json))
    }

    private fun showImage(json: String): String? {
        val show = objectAfter(json, "\"current_show\"") ?: return null
        return imageUrl(show)
    }

    private fun imageUrl(block: String): String? {
        // Widest first: this ends up as a player cover, not a list thumbnail.
        val full = objectAfter(block, "\"fullImage\"")?.let { stringField(it, "url") }
        if (!full.isNullOrBlank()) return full
        val list = objectAfter(block, "\"listImage\"")?.let { stringField(it, "url") }
        return list?.takeIf { it.isNotBlank() }
    }

    /**
     * The brace-balanced object that follows [key].
     *
     * Balanced rather than "up to the next `}`", because these objects nest — `current_show`
     * contains three image objects, and stopping at the first closing brace would return a third
     * of it.
     */
    private fun objectAfter(json: String, key: String): String? {
        val at = json.indexOf(key)
        if (at < 0) return null
        val open = json.indexOf('{', at + key.length)
        if (open < 0) return null
        // A null value: `"current_playlist_item": null` has no object at all, and the next `{`
        // belongs to something else entirely.
        val between = json.substring(at + key.length, open)
        if (between.contains("null")) return null
        var depth = 0
        var i = open
        while (i < json.length) {
            when (json[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(open, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun stringField(block: String, field: String): String? =
        Regex("\"$field\"\\s*:\\s*\"([^\"]*)\"").find(block)?.groupValues?.get(1)
}
