package com.lightphone.spotify.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodePagingTest {

    @Test
    fun `newest first walks forward from zero`() {
        assertEquals(
            EpisodePaging.PageRequest(0, 50),
            EpisodePaging.nextRequest(loaded = 0, total = 0, oldestFirst = false),
        )
        assertEquals(
            EpisodePaging.PageRequest(50, 50),
            EpisodePaging.nextRequest(loaded = 50, total = 300, oldestFirst = false),
        )
    }

    @Test
    fun `newest first last page is short rather than overrunning the feed`() {
        assertEquals(
            EpisodePaging.PageRequest(100, 20),
            EpisodePaging.nextRequest(loaded = 100, total = 120, oldestFirst = false),
        )
    }

    @Test
    fun `oldest first starts at the far end of the feed`() {
        // 120 episodes, newest at offset 0: the 50 oldest are offsets 70..119.
        assertEquals(
            EpisodePaging.PageRequest(70, 50),
            EpisodePaging.nextRequest(loaded = 0, total = 120, oldestFirst = true),
        )
        assertEquals(
            EpisodePaging.PageRequest(20, 50),
            EpisodePaging.nextRequest(loaded = 50, total = 120, oldestFirst = true),
        )
        // The final page is the newest 20, and it stops at offset 0 rather than going negative.
        assertEquals(
            EpisodePaging.PageRequest(0, 20),
            EpisodePaging.nextRequest(loaded = 100, total = 120, oldestFirst = true),
        )
    }

    @Test
    fun `oldest first pages cover the whole feed exactly once`() {
        val total = 137
        val covered = mutableListOf<Int>()
        var loaded = 0
        while (true) {
            val request = EpisodePaging.nextRequest(loaded, total, oldestFirst = true) ?: break
            covered += (request.offset until request.offset + request.limit)
            loaded += request.limit
        }
        assertEquals((0 until total).toList(), covered.sorted())
    }

    @Test
    fun `nothing left to fetch once everything is loaded`() {
        assertNull(EpisodePaging.nextRequest(loaded = 120, total = 120, oldestFirst = false))
        assertNull(EpisodePaging.nextRequest(loaded = 120, total = 120, oldestFirst = true))
        assertNull(EpisodePaging.nextRequest(loaded = 0, total = 0, oldestFirst = true))
    }

    @Test
    fun `oldest first needs the total before it can ask for anything`() {
        assertTrue(EpisodePaging.needsTotal(total = 0, oldestFirst = true))
        assertFalse(EpisodePaging.needsTotal(total = 120, oldestFirst = true))
        assertFalse(EpisodePaging.needsTotal(total = 0, oldestFirst = false))
    }

    @Test
    fun `merge reverses a page when the list reads oldest first`() {
        // The API hands back newest-first inside the window, so the oldest of the three leads.
        val page = listOf("c", "b", "a")
        assertEquals(listOf("a", "b", "c"), EpisodePaging.merge(emptyList(), page, true) { it })
        assertEquals(listOf("c", "b", "a"), EpisodePaging.merge(emptyList(), page, false) { it })
    }

    @Test
    fun `merge drops ids already loaded`() {
        // A new episode published mid-scroll shifts every offset by one and repeats a boundary row.
        val loaded = listOf("a", "b", "c")
        assertEquals(
            listOf("a", "b", "c", "d"),
            EpisodePaging.merge(loaded, listOf("c", "d"), oldestFirst = false) { it },
        )
    }

    @Test
    fun `merge of an empty page changes nothing`() {
        val loaded = listOf("a", "b")
        assertEquals(loaded, EpisodePaging.merge(loaded, emptyList(), oldestFirst = true) { it })
    }
}
