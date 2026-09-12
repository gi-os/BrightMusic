package com.lightphone.spotify.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The decode hint is passed straight to Coil, whose `Dimension.Pixels` throws
 * `IllegalArgumentException: "px must be > 0."` on anything at or below zero. It throws from
 * inside composition, so it killed the process rather than the image: v0.67 crash-looped on
 * launch when a subcomposition of the expanded player ran at zero width and every size derived
 * from that width rounded to 0 px.
 */
class UsableDecodePxTest {

    @Test
    fun `a real size is passed through`() {
        assertEquals(1, usableDecodePx(1))
        assertEquals(320, usableDecodePx(320))
        assertEquals(Int.MAX_VALUE, usableDecodePx(Int.MAX_VALUE))
    }

    @Test
    fun `zero and below are dropped rather than handed to Coil`() {
        assertNull(usableDecodePx(0))
        assertNull(usableDecodePx(-1))
        assertNull(usableDecodePx(Int.MIN_VALUE))
    }

    @Test
    fun `no requested size stays no requested size`() {
        assertNull(usableDecodePx(null))
    }
}
