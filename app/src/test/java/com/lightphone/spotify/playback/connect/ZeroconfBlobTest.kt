package com.lightphone.spotify.playback.connect

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The claim crypto, checked against the thing that has to read it.
 *
 * Neither layer can be verified by inspection: a wrong key derivation or a wrong nesting order
 * produces bytes that look perfectly fine and are rejected silently by a speaker across the room. So
 * each test drives the encoder and then the *decoder side* — the reimplementations of librespot's
 * `Credentials::with_blob` and `handle_add_user` that live next to the encoder — and asserts what a
 * receiver would end up holding.
 */
class ZeroconfBlobTest {

    private val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(1234L) }

    @Test
    fun `a credentials blob decodes back to the credential that went in`() {
        // Length chosen to be neither a multiple of 16 nor under the varint's one-byte ceiling, so
        // the padding and the two-byte length path are both exercised.
        val authData = ByteArray(203) { (it * 7 + 3).toByte() }

        val blob = ZeroconfBlob.encodeCredentialsBlob(
            username = "someaccount",
            authType = STORED_CREDENTIALS,
            authData = authData,
            deviceId = RECEIVER_DEVICE_ID,
        )
        val decoded = ZeroconfBlob.decodeCredentialsBlob(blob, "someaccount", RECEIVER_DEVICE_ID)

        assertEquals(STORED_CREDENTIALS, decoded.authType)
        assertTrue(authData.contentEquals(decoded.authData))
    }

    @Test
    fun `the blob framing matches what the Spotify desktop client sends`() {
        // Golden vector. The layout was captured by standing up a fake receiver advertising
        // `_spotify-connect._tcp` with a DH key we held the private half of, then tapping it in the
        // Spotify desktop app and decrypting what arrived:
        //
        //   49 08 "someuser"  50 01  51 a8 01  <168 bytes>  00 00 00 00 00 00 00 00 09
        //
        // Two of the three tags were wrong before this, and the padding was plain zeroes. Neither
        // mistake is detectable against librespot — its decoder skips the tags and ignores the
        // padding — so only a byte-for-byte assertion protects this. The credential itself is a
        // stand-in of the same length, and so is the username; the real capture was of a live
        // account and neither half of it belongs in a public repo.
        val credential = ByteArray(168) { ('A' + (it % 26)).code.toByte() }

        val framed = ZeroconfBlob.framedPlaintext("someuser", STORED_CREDENTIALS, credential)

        assertEquals(EXPECTED_FRAMING, framed.hex())
        // 183 bytes of content, so nine of padding, and the count lands in the final byte.
        assertEquals(192, framed.size)
        assertEquals(9, framed.last().toInt())
    }

    @Test
    fun `a short credential still round-trips`() {
        // Under 16 bytes total the XOR layer never runs, which is a different path through both ends.
        val blob = ZeroconfBlob.encodeCredentialsBlob("u", 1, byteArrayOf(1, 2, 3), RECEIVER_DEVICE_ID)

        val decoded = ZeroconfBlob.decodeCredentialsBlob(blob, "u", RECEIVER_DEVICE_ID)

        assertTrue(byteArrayOf(1, 2, 3).contentEquals(decoded.authData))
    }

    @Test
    fun `a blob sealed for one receiver is useless to another`() {
        // The point of the device-id-keyed layer: this is why a stored credential cannot just be
        // forwarded, and why the claim has to read getInfo first.
        val blob = ZeroconfBlob.encodeCredentialsBlob(
            username = "someaccount",
            authType = STORED_CREDENTIALS,
            authData = ByteArray(64) { it.toByte() },
            deviceId = RECEIVER_DEVICE_ID,
        )

        val recovered = runCatching {
            ZeroconfBlob.decodeCredentialsBlob(blob, "someaccount", "0000000000000000000000000000000000000000")
        }.getOrNull()

        // Either the parse blew up on garbage or it produced something else; both are fine, silently
        // producing the real credential would not be.
        if (recovered != null) {
            assertNotEquals(ByteArray(64) { it.toByte() }.toList(), recovered.authData.toList())
        }
    }

    @Test
    fun `the username is part of the blob key`() {
        val blob = ZeroconfBlob.encodeCredentialsBlob("alice", 1, byteArrayOf(9, 9, 9), RECEIVER_DEVICE_ID)

        val recovered = runCatching {
            ZeroconfBlob.decodeCredentialsBlob(blob, "bob", RECEIVER_DEVICE_ID)
        }.getOrNull()

        if (recovered != null) {
            assertNotEquals(listOf<Byte>(9, 9, 9), recovered.authData.toList())
        }
    }

    @Test
    fun `the envelope round-trips between two independent parties`() {
        // Stands in for the receiver: it publishes a public key in getInfo and keeps the private half.
        val receiver = ZeroconfBlob.DhKeys.random(random)
        val payload = "a-base64-looking-payload==".toByteArray()

        val sealed = ZeroconfBlob.seal(payload, receiver.publicKey, random)
        val opened = ZeroconfBlob.open(sealed.blob, sealed.clientKey, receiver)

        assertTrue(payload.contentEquals(opened))
    }

    @Test
    fun `a tampered envelope fails its checksum instead of decrypting to noise`() {
        val receiver = ZeroconfBlob.DhKeys.random(random)
        val sealed = ZeroconfBlob.seal("payload".toByteArray(), receiver.publicKey, random)

        // Flip a bit in the ciphertext. The trailing HMAC is what a receiver checks first, and
        // ERROR-MAC is the failure we would otherwise be guessing at.
        val raw = java.util.Base64.getDecoder().decode(sealed.blob)
        raw[20] = (raw[20].toInt() xor 0x01).toByte()
        val tampered = java.util.Base64.getEncoder().encodeToString(raw)

        assertThrows(IllegalArgumentException::class.java) {
            ZeroconfBlob.open(tampered, sealed.clientKey, receiver)
        }
    }

    @Test
    fun `pbkdf2 matches the RFC 6070 vector`() {
        // The hand-rolled PBKDF2 is the one primitive here with no counterpart to test against, and
        // it is keyed on raw bytes precisely because JCA's char[] API would mangle them.
        val out = ByteArray(20)
        ZeroconfBlob.pbkdf2HmacSha1(
            password = "password".toByteArray(),
            salt = "salt".toByteArray(),
            iterations = 1,
            out = out,
            outLen = 20,
        )
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", out.hex())

        val second = ByteArray(20)
        ZeroconfBlob.pbkdf2HmacSha1("password".toByteArray(), "salt".toByteArray(), 2, second, 20)
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", second.hex())
    }

    @Test
    fun `dh keys never carry a sign byte`() {
        // BigInteger adds a leading zero whenever the top bit is set. Sending it would make the far
        // end derive a different secret from the same key, so the minimal encoding is load-bearing.
        repeat(20) {
            val keys = ZeroconfBlob.DhKeys.random(random)
            assertNotEquals(0.toByte(), keys.publicKey.first())
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        /** librespot's `AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS`. */
        const val STORED_CREDENTIALS = 1
        const val RECEIVER_DEVICE_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"

        /** See the golden-vector test; every byte of this was measured. */
        const val EXPECTED_FRAMING = "4908736f6d6575736572500151a8014142434445464748494a4b4c4d4e4f505152535455565758595a4142434445464748494a4b4c4d4e4f505152535455565758595a4142434445464748494a4b4c4d4e4f505152535455565758595a4142434445464748494a4b4c4d4e4f505152535455565758595a4142434445464748494a4b4c4d4e4f505152535455565758595a4142434445464748494a4b4c4d4e4f505152535455565758595a4142434445464748494a4b4c000000000000000009"
    }
}
