package com.lightphone.spotify.data

/**
 * One row of the playlists page: a playlist, or the folder that holds Spotify's mixes.
 *
 * Generic over the item so the rule below can be tested without building a Room entity, and so the
 * same fold works on whatever the page is showing.
 */
sealed interface PlaylistRow<out T> {
    data class Single<out T>(val item: T) : PlaylistRow<T>

    /** Two or more of Spotify's own mixes, shown as one tile. Never empty, never one. */
    data class Folder<out T>(val items: List<T>) : PlaylistRow<T>
}

/**
 * The playlists Spotify makes for you, folded into one row.
 *
 * Six Daily Mixes, Discover Weekly, Release Radar, On Repeat, Repeat Rewind and the rest are ten
 * tiles nobody chose to follow, sitting in front of the playlists you did. They are worth keeping —
 * they are most of what gets played — so they collapse into a single folder in the position the
 * first of them held, and the page reads as your own library again.
 *
 * **What counts.** A name Spotify only gives to its own generated playlists, *and* an owner that
 * either says Spotify or says nothing. The owner test is what stops a playlist of your own called
 * "On Repeat" from being swallowed; the "says nothing" half is there because the rootlist this app
 * reads comes from spclient, which does not always carry an owner name, and a mix with no owner
 * resolved yet is still obviously a mix. A playlist of yours with one of these names *and* an
 * unresolved owner is the one case this gets wrong, and it gets it back the moment the owner
 * resolves.
 *
 * **Two or more, or nothing.** A folder holding one mix is worse than the mix: same space, one more
 * tap. Below two, they stay where they are.
 */
object MadeForYou {

    const val TITLE = "Made for you"

    /** Covers on the folder's tile: a 2×2, like the mosaic Spotify generates for a coverless playlist. */
    const val COVER_TILES = 4

    /** Fewer than this and folding costs more than it saves. */
    const val MIN_TO_FOLD = 2

    private val SPOTIFY_OWNERS = setOf("spotify", "")

    /**
     * The names Spotify gives what it generates, matched whole rather than by substring.
     *
     * Whole, because "Songs On Repeat" is an editorial playlist someone chose to follow and
     * `contains("on repeat")` would swallow it. The cost of being strict is that a market where
     * these are translated gets no folder at all, which is the right way round: a page that looks
     * the way it always has, rather than a page missing playlists.
     */
    private val EXACT_NAMES = setOf(
        "discover weekly",
        "release radar",
        "on repeat",
        "repeat rewind",
        "time capsule",
        "your time capsule",
        "daily drive",
    )

    /** The one that is numbered: "Daily Mix 1" through "Daily Mix 6". */
    private const val NUMBERED_PREFIX = "daily mix"

    fun matches(name: String, ownerId: String, ownerName: String = ""): Boolean {
        val owner = ownerId.trim().lowercase()
        val ownerLabel = ownerName.trim().lowercase()
        if (owner !in SPOTIFY_OWNERS || (ownerLabel.isNotEmpty() && ownerLabel != "spotify")) {
            return false
        }
        val lowered = name.trim().lowercase()
        return lowered in EXACT_NAMES || lowered.startsWith(NUMBERED_PREFIX)
    }

    /**
     * Fold the mixes out of a list of playlists.
     *
     * The folder lands where the first mix was, so everything you put in your own library keeps the
     * place you are used to finding it in. Order inside the folder is the order they arrived in,
     * which is Spotify's — near enough to "most recent first" for the four covers on the tile.
     */
    fun <T> fold(
        items: List<T>,
        name: (T) -> String,
        ownerId: (T) -> String,
        ownerName: (T) -> String = { "" },
    ): List<PlaylistRow<T>> {
        val mixes = items.filter { matches(name(it), ownerId(it), ownerName(it)) }
        if (mixes.size < MIN_TO_FOLD) return items.map { PlaylistRow.Single(it) }

        val rows = mutableListOf<PlaylistRow<T>>()
        var folded = false
        for (item in items) {
            if (matches(name(item), ownerId(item), ownerName(item))) {
                if (!folded) {
                    rows += PlaylistRow.Folder(mixes)
                    folded = true
                }
                continue
            }
            rows += PlaylistRow.Single(item)
        }
        return rows
    }

    /**
     * How far into the *unfolded* list a row sits.
     *
     * The playlists page pages as it scrolls and asks for more by index. Folding shifts every index
     * after the folder, so without this the page would stop fetching about nine playlists early —
     * which looks exactly like a library that ends sooner than it does.
     */
    fun <T> underlyingIndex(rows: List<PlaylistRow<T>>, rowIndex: Int): Int {
        var count = 0
        for ((index, row) in rows.withIndex()) {
            if (index >= rowIndex) break
            count += when (row) {
                is PlaylistRow.Single -> 1
                is PlaylistRow.Folder -> row.items.size
            }
        }
        return count
    }
}
