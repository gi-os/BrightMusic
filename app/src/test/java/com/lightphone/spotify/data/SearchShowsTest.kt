package com.lightphone.spotify.data

import com.lightphone.spotify.data.webapi.SpotifyShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Podcast shows in search results.
 *
 * Adding a fifth result type meant rewriting `SearchRanking.interleave` from four fixed arguments to a
 * list of pools, which is shared by every search — so these also guard the four types that already
 * worked.
 */
class SearchShowsTest {

    private fun show(id: String, name: String, publisher: String = "Pub") = SpotifyShow(
        id = id,
        uri = "spotify:show:$id",
        name = name,
        publisher = publisher,
    )

    private fun track(id: String, name: String, popularity: Int = 50) = SpotifyTrack(
        id = id,
        uri = "spotify:track:$id",
        name = name,
        popularity = popularity,
    )

    @Test
    fun `an exact show name match can win the top result`() {
        val results = SearchResults(
            query = "hard fork",
            tracks = listOf(track("t1", "Something Else")),
            shows = listOf(show("s1", "Hard Fork")),
        )

        val ranked = SearchRanking.rank("hard fork", results)

        assertTrue(ranked.topResult is SearchResultItem.Show)
        assertEquals("s1", ranked.topResult?.id)
    }

    @Test
    fun `shows appear in the interleaved remainder without displacing tracks`() {
        val results = SearchResults(
            query = "daily",
            tracks = listOf(track("t1", "Daily"), track("t2", "Daily II")),
            shows = listOf(show("s1", "The Daily"), show("s2", "Daily Show")),
        )

        val ranked = SearchRanking.rank("daily", results)
        val items = ranked.rankedItems

        // Both types survive the round-robin, and the top result is not repeated below it.
        assertTrue(items.any { it is SearchResultItem.Show })
        assertTrue(items.any { it is SearchResultItem.Track })
        assertFalse(items.any { it.uri == ranked.topResult?.uri })
    }

    @Test
    fun `a music query with no shows ranks exactly as before`() {
        val results = SearchResults(
            query = "aphex",
            tracks = listOf(track("t1", "Aphex Twin Mix"), track("t2", "Other")),
        )

        val ranked = SearchRanking.rank("aphex", results)

        assertEquals("t1", ranked.topResult?.id)
        assertTrue(ranked.rankedItems.all { it is SearchResultItem.Track })
    }

    @Test
    fun `the Podcasts filter shows only shows, and All still leads with the top result`() {
        val base = SearchResults(
            query = "daily",
            tracks = listOf(track("t1", "Daily")),
            shows = listOf(show("s1", "The Daily")),
        )
        val ranked = SearchRanking.rank("daily", base)
        val results = base.copy(topResult = ranked.topResult, rankedItems = ranked.rankedItems)

        val (showsTop, showsRest) = results.itemsForFilter(SearchFilter.Shows)
        assertEquals(null, showsTop)
        assertEquals(listOf("s1"), showsRest.map { it.id })
        assertTrue(showsRest.all { it is SearchResultItem.Show })

        val (allTop, _) = results.itemsForFilter(SearchFilter.All)
        assertEquals(ranked.topResult?.uri, allTop?.uri)
    }

    @Test
    fun `a results set holding only shows is not empty`() {
        assertTrue(SearchResults(query = "x").isEmpty())
        assertFalse(SearchResults(query = "x", shows = listOf(show("s1", "A Show"))).isEmpty())
    }

    @Test
    fun `a show with no publisher does not render a dangling separator`() {
        val item = SearchResultItem.Show(show("s1", "A Show", publisher = ""))

        assertEquals("Podcast", item.subtitle)
    }
}
