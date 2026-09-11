package com.lightphone.spotify.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The guard in front of Coil's `size(px)`, which is `require(px > 0)`.
 *
 * This is worth pinning down because the failure is not a bad-looking cover: the request is built
 * during composition, so a zero threw out of the measure pass and closed the app on launch
 * (light-reports#355). The regression to watch for is someone "simplifying" this back to a plain
 * null check on the Dp.
 */
class DecodeSizePxTest {

    @Test
    fun `a real size is passed through`() {
        assertEquals(320, decodeSizePxOrNull(320))
        assertEquals(1, decodeSizePxOrNull(1))
    }

    @Test
    fun `no requested size stays unsized`() {
        assertNull(decodeSizePxOrNull(null))
    }

    @Test
    fun `zero is unsized rather than a crash`() {
        // What the player produced while the overlay NavHost was still measured at 0x0:
        // maxWidth 0.dp / DesignWidthPx * 320f rounds to 0 px.
        assertNull(decodeSizePxOrNull(0))
    }

    @Test
    fun `a negative size is unsized too`() {
        assertNull(decodeSizePxOrNull(-1))
    }
}
