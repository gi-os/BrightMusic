package com.lightphone.spotify.radio.recognize

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShazamTokenPayloadTest {

    private fun jwt(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"ES256","kid":"K"}""".toByteArray())
        val payload = enc.encodeToString(payloadJson.toByteArray())
        return "$header.$payload.c2ln"
    }

    @Test
    fun `a bare jwt is accepted as-is`() {
        val t = jwt("""{"iss":"TEAM","exp":1770000000}""")
        assertEquals(t, ShazamTokenPayload.parse("  $t\n"))
    }

    @Test
    fun `the json wrapper is accepted too`() {
        val t = jwt("""{"exp":1770000000}""")
        assertEquals(t, ShazamTokenPayload.parse("""{"type":"shazam","token":"$t"}"""))
    }

    @Test
    fun `somebody else's qr is refused`() {
        // The bridge QR must not end up stored as a Shazam token just because it was scanned
        // on the wrong screen.
        assertNull(ShazamTokenPayload.parse("""{"type":"owntone","url":"http://x","token":"abc"}"""))
        assertNull(ShazamTokenPayload.parse("just some text"))
    }

    @Test
    fun `expiry is read out of the payload`() {
        assertEquals(1770000000L, ShazamTokenPayload.expiryEpochSeconds(jwt("""{"exp":1770000000}""")))
    }

    @Test
    fun `a token without exp has no expiry rather than a fake one`() {
        assertNull(ShazamTokenPayload.expiryEpochSeconds(jwt("""{"iss":"TEAM"}""")))
    }

    @Test
    fun `expired and current tokens are told apart`() {
        val past = jwt("""{"exp":1000000000}""")
        val future = jwt("""{"exp":4000000000}""")
        assertTrue(ShazamTokenPayload.isExpired(past))
        assertFalse(ShazamTokenPayload.isExpired(future))
    }
}
