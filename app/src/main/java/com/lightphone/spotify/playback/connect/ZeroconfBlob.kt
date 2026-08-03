package com.lightphone.spotify.playback.connect

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto behind claiming a Spotify Connect receiver over ZeroConf (`action=addUser`).
 *
 * ### Why this is in Kotlin and not the Rust core
 * The obvious home for this was `rust/spotify-core`, next to the librespot session that owns the
 * credentials. Everything here is standard primitives, though — SHA-1, HMAC-SHA1, PBKDF2, AES-CTR,
 * AES-ECB and one modular exponentiation — all of which the platform already ships in `javax.crypto`
 * and `BigInteger`. Doing it here costs no new Rust dependency, no UniFFI surface and no cargo
 * rebuild per iteration, and it is unit-testable on the JVM.
 *
 * ### The protocol, from the far end's point of view
 * A receiver's ZeroConf server is the *decoder*; this file is the encoder for it, so every step below
 * is the exact inverse of `librespot-discovery`'s `handle_add_user` and
 * `librespot_core::authentication::Credentials::with_blob`. Two nested layers:
 *
 *  1. **The transport envelope**, keyed by a Diffie-Hellman exchange against the `publicKey` the
 *     receiver hands out in `getInfo`: `base64(iv || AES128-CTR(payload) || HMAC-SHA1(cipher))`.
 *  2. **The credentials blob** inside it, keyed by the *receiver's* `deviceID` and the account
 *     username. This is why a stored credential cannot simply be forwarded: a receiver can only
 *     unwrap a blob that was sealed for its own device id.
 *
 * ### The one part that is inferred rather than documented
 * Layer 2's plaintext is a tag/length/value structure, and both open implementations (librespot and
 * librespot-java) *skip* the tag bytes and the second field without checking them, so their decoders
 * do not pin down what a real client sends. Spotify has never published it. The field order and the
 * varint encoding are forced by the decoder and are certain; [TAG_VERSION], [TAG_AUTH_TYPE],
 * [TAG_AUTH_DATA] and the choice to put the username in the skipped field are inference. A receiver
 * running Spotify's own eSDK that validates them will answer `addUser` with a non-101 status, which
 * [ZeroconfClaim] surfaces verbatim — so this is a wrong constant to change, not a silence to chase.
 */
internal object ZeroconfBlob {

    /** Result of sealing a payload for a receiver: the two form fields `addUser` needs. */
    data class Sealed(val blob: String, val clientKey: String)

    // --- layer 1: the DH-keyed transport envelope ----------------------------

    /**
     * Seal [payload] for a receiver whose `getInfo` returned [receiverPublicKey] (raw, already
     * base64-decoded).
     *
     * [random] is injectable so a test can pin the IV and the DH private key.
     */
    fun seal(payload: ByteArray, receiverPublicKey: ByteArray, random: SecureRandom): Sealed =
        seal(payload, receiverPublicKey, DhKeys.random(random), ByteArray(16).also(random::nextBytes))

    fun seal(
        payload: ByteArray,
        receiverPublicKey: ByteArray,
        keys: DhKeys,
        iv: ByteArray,
    ): Sealed {
        val shared = keys.sharedSecret(receiverPublicKey)

        // SHA-1 of the shared secret, first 16 bytes. Two purpose-separated keys come off it, which
        // is what stops the checksum key and the cipher key being the same bytes.
        val baseKey = sha1(shared).copyOf(16)
        val checksumKey = hmacSha1(baseKey, "checksum".toByteArray())
        val encryptionKey = hmacSha1(baseKey, "encryption".toByteArray()).copyOf(16)

        val encrypted = aesCtr(encryptionKey, iv, payload)
        val checksum = hmacSha1(checksumKey, encrypted)

        return Sealed(
            blob = base64(iv + encrypted + checksum),
            clientKey = base64(keys.publicKey),
        )
    }

    // --- layer 2: the credentials blob ---------------------------------------

    /**
     * Build the credentials blob a receiver can unwrap, base64-encoded.
     *
     * The base64 *string* is the payload for [seal], not the raw bytes: the receiver base64-decodes
     * what comes out of the envelope before it touches AES. Getting that nesting wrong is invisible
     * until the far end fails to parse.
     *
     * [deviceId] is the receiver's `deviceID` from `getInfo`. The blob is bound to it.
     */
    fun encodeCredentialsBlob(
        username: String,
        authType: Int,
        authData: ByteArray,
        deviceId: String,
    ): String {
        val plaintext = buildPlaintext(username, authType, authData)
        // AES-ECB walks whole blocks and the decoder uses `chunks_exact`, which silently drops a
        // trailing partial block — a blob whose length is not a multiple of 16 loses its tail. The
        // decoder stops once it has auth_data, so trailing zeroes are ignored.
        val padded = plaintext.copyOf(roundUpTo16(plaintext.size))
        obfuscate(padded)
        return base64(aesEcbEncrypt(blobKey(username, deviceId), padded))
    }

    private fun buildPlaintext(username: String, authType: Int, authData: ByteArray): ByteArray {
        val out = ArrayList<Byte>(username.length + authData.size + 8)
        out.add(TAG_VERSION)
        // Skipped by every decoder we can read. The username is the only datum that plausibly
        // belongs here, and a receiver that does check it can cross-check it against the `userName`
        // form field.
        writeLengthPrefixed(out, username.toByteArray())
        out.add(TAG_AUTH_TYPE)
        writeVarint(out, authType)
        out.add(TAG_AUTH_DATA)
        writeLengthPrefixed(out, authData)
        return out.toByteArray()
    }

    /**
     * The XOR layer between the plaintext and AES.
     *
     * The decoder undoes it *after* decrypting, walking backwards: `p[t] = x[t] xor x[t-16]` for
     * `t >= 16`. Forwards and in place is the exact inverse, because by the time index `t` is
     * written, `t-16` already holds the obfuscated byte the decoder will read there.
     */
    private fun obfuscate(data: ByteArray) {
        for (t in 16 until data.size) {
            data[t] = (data[t].toInt() xor data[t - 16].toInt()).toByte()
        }
    }

    private fun deobfuscate(data: ByteArray) {
        for (t in data.size - 1 downTo 16) {
            data[t] = (data[t].toInt() xor data[t - 16].toInt()).toByte()
        }
    }

    /**
     * AES-192 key for the blob layer, from the receiver's device id and the username.
     *
     * PBKDF2 is hand-rolled because the password is 20 raw bytes (a SHA-1 digest) and JCA's
     * `PBEKeySpec` takes a `char[]`, which would UTF-8-encode those bytes into something else.
     */
    private fun blobKey(username: String, deviceId: String): ByteArray {
        val secret = sha1(deviceId.toByteArray())
        val key = ByteArray(24)
        pbkdf2HmacSha1(secret, username.toByteArray(), iterations = 0x100, out = key, outLen = 20)
        sha1(key.copyOf(20)).copyInto(key, 0, 0, 20)
        // Big-endian length of the hash in front of it. Part of the key, not a header.
        key[20] = 0
        key[21] = 0
        key[22] = 0
        key[23] = 20
        return key
    }

    // --- the inverse direction, used only by tests ---------------------------

    /** Decoded credentials, mirroring librespot's `Credentials`. */
    data class DecodedBlob(val authType: Int, val authData: ByteArray) {
        override fun equals(other: Any?): Boolean = other is DecodedBlob &&
            authType == other.authType && authData.contentEquals(other.authData)

        override fun hashCode(): Int = 31 * authType + authData.contentHashCode()
    }

    /**
     * Reimplementation of `Credentials::with_blob`, kept so the encoder can be tested against the
     * thing that will actually read it rather than against itself.
     */
    fun decodeCredentialsBlob(blobBase64: String, username: String, deviceId: String): DecodedBlob {
        val data = aesEcbDecrypt(blobKey(username, deviceId), base64Decode(blobBase64))
        deobfuscate(data)
        var i = 0
        fun u8(): Int = data[i++].toInt() and 0xff
        fun varint(): Int {
            val lo = u8()
            if (lo and 0x80 == 0) return lo
            return (lo and 0x7f) or (u8() shl 7)
        }
        u8() // version tag
        // Read the length first and advance after: `i += varint()` loads `i` before the call and
        // then overwrites the cursor the call moved, silently swallowing the length bytes.
        val skip = varint()
        i += skip
        u8() // auth type tag
        val authType = varint()
        u8() // auth data tag
        val len = varint()
        return DecodedBlob(authType, data.copyOfRange(i, i + len))
    }

    /** The receiver's side of [seal], so a test can prove the envelope round-trips. */
    fun open(blobBase64: String, clientKeyBase64: String, receiverKeys: DhKeys): ByteArray {
        val envelope = base64Decode(blobBase64)
        val shared = receiverKeys.sharedSecret(base64Decode(clientKeyBase64))
        val baseKey = sha1(shared).copyOf(16)
        val checksumKey = hmacSha1(baseKey, "checksum".toByteArray())
        val encryptionKey = hmacSha1(baseKey, "encryption".toByteArray()).copyOf(16)

        val iv = envelope.copyOfRange(0, 16)
        val encrypted = envelope.copyOfRange(16, envelope.size - 20)
        val checksum = envelope.copyOfRange(envelope.size - 20, envelope.size)
        require(hmacSha1(checksumKey, encrypted).contentEquals(checksum)) { "MAC mismatch" }
        // CTR is its own inverse.
        return aesCtr(encryptionKey, iv, encrypted)
    }

    // --- primitives ----------------------------------------------------------

    /**
     * Diffie-Hellman over Spotify's fixed 768-bit group, byte-for-byte compatible with
     * `librespot_core::diffie_hellman`: a 95-byte **little-endian** private key, with public keys and
     * shared secrets as minimal big-endian byte strings.
     *
     * The little-endian read and the minimal encoding both matter — a leading zero byte, which
     * `BigInteger.toByteArray` adds for sign whenever the top bit is set, makes the far end derive a
     * different key.
     */
    class DhKeys private constructor(private val privateKey: BigInteger) {
        val publicKey: ByteArray = GENERATOR.modPow(privateKey, PRIME).toMinimalBytes()

        fun sharedSecret(remoteKey: ByteArray): ByteArray =
            BigInteger(1, remoteKey).modPow(privateKey, PRIME).toMinimalBytes()

        companion object {
            fun random(random: SecureRandom): DhKeys {
                val bytes = ByteArray(95).also(random::nextBytes)
                return DhKeys(BigInteger(1, bytes.reversedArray()))
            }

            private val GENERATOR = BigInteger.valueOf(2)
            // Spotify's fixed 768-bit MODP group, as a hex literal rather than a byte list:
            // the same constant transcribed by hand twice is the same constant typo'd twice.
            private val PRIME = BigInteger(
                "ffffffffffffffffc90fdaa22168c234c4c6628b80dc1cd129024e088a67cc74" +
                    "020bbea63b139b22514a08798e3404ddef9519b3cd3a431b302b0a6df25f1437" +
                    "4fe1356d6d51c245e485b576625e7ec6f44c42e9a63a3620ffffffffffffffff",
                16,
            )
        }
    }

    fun sha1(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(data)

    fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(key, "HmacSHA1"))
            doFinal(data)
        }

    /** RFC 2898 PBKDF2-HMAC-SHA1 over a raw-byte password. [outLen] is always <= 20 here. */
    fun pbkdf2HmacSha1(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        out: ByteArray,
        outLen: Int,
    ) {
        // One block covers any outLen up to HMAC-SHA1's 20 bytes; asserting that is cheaper than a
        // block loop nothing will ever use.
        require(outLen <= 20) { "pbkdf2 output longer than one SHA-1 block" }
        var u = hmacSha1(password, salt + byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(iterations - 1) {
            u = hmacSha1(password, u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        result.copyInto(out, 0, 0, outLen)
    }

    fun aesCtr(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    private fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    private fun aesEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(data)
        }

    // --- encoding helpers ----------------------------------------------------

    /**
     * The decoder's `read_int`: one byte below 0x80, otherwise seven low bits plus a continuation
     * byte. Two bytes maximum, so 0x3fff is the ceiling — well clear of a stored credential, which
     * runs to a few hundred bytes.
     */
    private fun writeVarint(out: MutableList<Byte>, value: Int) {
        require(value in 0..0x3fff) { "varint out of range: $value" }
        if (value < 0x80) {
            out.add(value.toByte())
        } else {
            out.add(((value and 0x7f) or 0x80).toByte())
            out.add((value shr 7).toByte())
        }
    }

    private fun writeLengthPrefixed(out: MutableList<Byte>, data: ByteArray) {
        writeVarint(out, data.size)
        data.forEach(out::add)
    }

    private fun roundUpTo16(size: Int): Int = (size + 15) / 16 * 16

    private fun base64(data: ByteArray): String = Base64.getEncoder().encodeToString(data)

    private fun base64Decode(text: String): ByteArray = Base64.getDecoder().decode(text.trim())

    // Inferred, not documented — see the object comment.
    private const val TAG_VERSION: Byte = 0x49
    private const val TAG_AUTH_TYPE: Byte = 0x4c
    private const val TAG_AUTH_DATA: Byte = 0x50
}

/**
 * Big-endian bytes with no sign byte, matching `BigUint::to_bytes_be`.
 *
 * `BigInteger.toByteArray` prepends a zero whenever the top bit is set, and that extra byte changes
 * the value the far end reads a key as.
 */
private fun BigInteger.toMinimalBytes(): ByteArray {
    val bytes = toByteArray()
    return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
}
