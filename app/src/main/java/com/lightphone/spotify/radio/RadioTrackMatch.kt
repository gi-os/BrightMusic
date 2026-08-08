package com.lightphone.spotify.radio

/**
 * Turning a radio station's now-playing string into something Spotify can be asked about.
 *
 * Stations broadcast one free-text field and no agreement about what goes in it. Icecast mounts
 * almost always send `Artist - Title`; NTS live sends a *show* name ("Charlie Bones"), which is
 * not a track at all and must not be searched, or the player would show a random cover for
 * whatever the search happened to return.
 *
 * So the rule is deliberately strict: a separator with text on both sides, or no match. Missing a
 * real track is cheap — the screen looks exactly as it did before — while a wrong match puts the
 * wrong artwork on screen and offers to save the wrong song to the user's library.
 *
 * Pure and side-effect free so it can be tested without a radio or a network; see
 * `RadioTrackMatchTest`.
 */
object RadioTrackMatch {

    data class Parsed(val artist: String, val title: String) {
        /**
         * What to send to `/search`. No `artist:`/`track:` field filters: stations regularly put
         * the title first, or use a featured artist Spotify files differently, and a field query
         * turns a near miss into no result at all. A plain query lets Spotify's own ranking do
         * the fuzzy work.
         */
        val query: String get() = "$artist $title"
    }

    /** Separators seen in the wild, longest first so an en dash is not caught by the hyphen. */
    private val SEPARATORS = listOf(" – ", " — ", " - ", " | ")

    /**
     * Trailing noise broadcasters append. Stripped because it survives into the query and pushes
     * the real track down the results.
     */
    private val NOISE = Regex(
        """\s*[\(\[](official|official video|official audio|audio|video|hd|remaster(ed)?|live)[^\)\]]*[\)\]]\s*""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(nowPlaying: String?): Parsed? {
        val raw = nowPlaying?.trim().orEmpty()
        if (raw.isBlank()) return null
        val separator = SEPARATORS.firstOrNull { raw.contains(it) } ?: return null
        val artist = raw.substringBefore(separator).clean()
        val title = raw.substringAfter(separator).clean()
        if (artist.isBlank() || title.isBlank()) return null
        // A slogan is not an artist. Plenty of mounts broadcast "MyRadio.fm - The best music all
        // day" between songs, and that parses perfectly well into two fields — so length is the
        // only cheap signal that separates it from a real credit.
        if (artist.length > MAX_FIELD || title.length > MAX_FIELD) return null
        return Parsed(artist = artist, title = title)
    }

    private fun String.clean(): String = replace(NOISE, " ").trim().trim('-', '–', '—', '|', ' ')

    /**
     * Longest plausible artist or title. 48, not 80: the unit test's station slogan split into a
     * 61-character "artist" and passed at 80, which is exactly the wrong match this guard exists
     * to stop. The longest real credit in the test set is 27 characters.
     */
    private const val MAX_FIELD = 48
}
