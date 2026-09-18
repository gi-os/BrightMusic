package com.lightphone.spotify.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which playlists the "Made for you" folder swallows, and where it sits once it has.
 *
 * The tests that matter are the refusals: this rule hides rows, and a rule that hides a playlist
 * someone made is worse than one that leaves a mix on the page.
 */
class MadeForYouTest {

    private data class P(
        val name: String,
        val owner: String = "spotify",
        val ownerName: String = "Spotify",
    )

    private fun fold(items: List<P>) =
        MadeForYou.fold(items, name = { it.name }, ownerId = { it.owner }, ownerName = { it.ownerName })

    private val mine = P("Kitchen", owner = "gio", ownerName = "Gio")
    private val alsoMine = P("Driving", owner = "gio", ownerName = "Gio")

    @Test
    fun `every name Spotify generates is recognised`() {
        val names = listOf(
            "Daily Mix 1",
            "Daily Mix 6",
            "Discover Weekly",
            "Release Radar",
            "On Repeat",
            "Repeat Rewind",
            "Your Time Capsule",
            "Daily Drive",
        )
        for (name in names) {
            assertTrue(name, MadeForYou.matches(name, ownerId = "spotify", ownerName = "Spotify"))
        }
        // Case and spacing come back differently in different markets.
        assertTrue(MadeForYou.matches("  daily mix 2 ", "SPOTIFY", "spotify"))
    }

    @Test
    fun `a playlist of your own with one of those names is left alone`() {
        // The whole reason the owner is checked. Somebody's own "On Repeat" is not Spotify's.
        assertFalse(MadeForYou.matches("On Repeat", ownerId = "gio", ownerName = "Gio"))
    }

    @Test
    fun `an unresolved owner still reads as Spotify's`() {
        // The rootlist comes from spclient, which does not always carry an owner. A mix with no
        // owner resolved yet is still obviously a mix, and waiting would leave the page cluttered
        // on exactly the cold starts where it is most cluttered.
        assertTrue(MadeForYou.matches("Discover Weekly", ownerId = "", ownerName = ""))
    }

    @Test
    fun `an ordinary playlist is never folded`() {
        assertFalse(MadeForYou.matches("Kitchen", ownerId = "gio", ownerName = "Gio"))
        assertFalse(MadeForYou.matches("Daily Grind", ownerId = "spotify", ownerName = "Spotify"))
    }

    @Test
    fun `an editorial playlist that merely contains one of the names is left alone`() {
        // Spotify owns this one too, but it is something you chose to follow rather than something
        // generated for you, and a substring match would have hidden it.
        assertFalse(MadeForYou.matches("Songs On Repeat", ownerId = "spotify", ownerName = "Spotify"))
        assertFalse(MadeForYou.matches("Discover Weekly Picks", "spotify", "Spotify"))
    }

    @Test
    fun `the folder takes the place of the first mix`() {
        val mixOne = P("Daily Mix 1")
        val mixTwo = P("Discover Weekly")
        val rows = fold(listOf(mine, mixOne, alsoMine, mixTwo))

        assertEquals(3, rows.size)
        assertEquals(PlaylistRow.Single(mine), rows[0])
        assertEquals(PlaylistRow.Folder(listOf(mixOne, mixTwo)), rows[1])
        assertEquals(PlaylistRow.Single(alsoMine), rows[2])
    }

    @Test
    fun `one mix on its own is not worth a folder`() {
        // Same space on screen, one more tap to reach it.
        val rows = fold(listOf(mine, P("Daily Mix 1")))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is PlaylistRow.Single })
    }

    @Test
    fun `a library with no mixes is untouched`() {
        val rows = fold(listOf(mine, alsoMine))
        assertEquals(listOf(PlaylistRow.Single(mine), PlaylistRow.Single(alsoMine)), rows)
    }

    @Test
    fun `the folder keeps Spotify's order, which is recent first`() {
        // The tile draws the first four covers, so the order inside the folder is what decides
        // which four they are.
        val rows = fold(listOf(P("Daily Mix 1"), P("Daily Mix 2"), P("Discover Weekly")))
        val folder = rows.single() as PlaylistRow.Folder
        assertEquals(
            listOf("Daily Mix 1", "Daily Mix 2", "Discover Weekly"),
            folder.items.map { it.name },
        )
    }

    @Test
    fun `paging counts through the folder rather than over it`() {
        // A folder holding nine mixes is one row and nine playlists. Asking for more by the row
        // number would stop paging nine short of the end, which reads as a library that ends early.
        val rows = fold(listOf(mine, P("Daily Mix 1"), P("Daily Mix 2"), P("Daily Mix 3"), alsoMine))
        assertEquals(0, MadeForYou.underlyingIndex(rows, 0))
        assertEquals(1, MadeForYou.underlyingIndex(rows, 1))
        // Past the folder: one playlist plus the three it holds.
        assertEquals(4, MadeForYou.underlyingIndex(rows, 2))
        assertEquals(5, MadeForYou.underlyingIndex(rows, 3))
        // Past the end answers with the whole length rather than throwing: the caller hands it a
        // "one past the last visible row" every time it scrolls to the bottom.
        assertEquals(5, MadeForYou.underlyingIndex(rows, 99))
    }
}
