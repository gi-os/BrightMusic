package com.lightphone.spotify.playback.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPinPlanTest {

    private val liked = listOf("t1", "t2", "t3", "t4", "t5")

    @Test
    fun `only the newest up to the limit are pinned`() {
        assertEquals(
            listOf("t1", "t2", "t3"),
            AutoPinPlan.likedToPin(liked, alreadyPinned = emptySet(), limit = 3),
        )
    }

    @Test
    fun `tracks already on the phone are not queued again`() {
        assertEquals(
            listOf("t2"),
            AutoPinPlan.likedToPin(liked, alreadyPinned = setOf("t1", "t3"), limit = 3),
        )
    }

    @Test
    fun `a limit of zero downloads nothing`() {
        assertTrue(AutoPinPlan.likedToPin(liked, emptySet(), limit = 0).isEmpty())
        assertTrue(AutoPinPlan.likedToPin(liked, emptySet(), limit = -1).isEmpty())
    }

    @Test
    fun `a limit past the end of the library is not an error`() {
        assertEquals(liked, AutoPinPlan.likedToPin(liked, emptySet(), limit = 500))
    }

    @Test
    fun `the window drops what has fallen off the far end`() {
        // t4 and t5 were pinned when the window was bigger, or when they were newer.
        assertEquals(
            listOf("t4", "t5"),
            AutoPinPlan.likedToDrop(liked, pinnedForLiked = setOf("t1", "t4", "t5"), limit = 3)
                .sorted(),
        )
    }

    @Test
    fun `nothing inside the window is dropped`() {
        assertTrue(
            AutoPinPlan.likedToDrop(liked, pinnedForLiked = setOf("t1", "t2"), limit = 3).isEmpty(),
        )
    }

    @Test
    fun `a track no longer liked at all is dropped`() {
        assertEquals(
            listOf("gone"),
            AutoPinPlan.likedToDrop(liked, pinnedForLiked = setOf("t1", "gone"), limit = 5),
        )
    }

    @Test
    fun `a mix reordered is the same mix`() {
        assertFalse(AutoPinPlan.mixChanged(listOf("a", "b", "c"), setOf("c", "b", "a")))
    }

    @Test
    fun `a mix with different tracks has changed`() {
        assertTrue(AutoPinPlan.mixChanged(listOf("a", "b", "d"), setOf("a", "b", "c")))
        // Nothing pinned yet is a change: it has to be downloaded a first time.
        assertTrue(AutoPinPlan.mixChanged(listOf("a"), emptySet()))
    }

    @Test
    fun `only the first few mixes are kept`() {
        val ids = listOf("m1", "m2", "m3", "m4", "m5", "m6")
        assertEquals(listOf("m1", "m2"), AutoPinPlan.mixesToPin(ids, limit = 2))
        assertTrue(AutoPinPlan.mixesToPin(ids, limit = 0).isEmpty())
    }

    @Test
    fun `the default liked limit is one the settings screen offers`() {
        // Otherwise the screen shows nothing selected on a fresh install.
        assertTrue(AutoPinPlan.DEFAULT_LIKED_LIMIT in AutoPinPlan.LIKED_LIMIT_CHOICES)
    }
}
