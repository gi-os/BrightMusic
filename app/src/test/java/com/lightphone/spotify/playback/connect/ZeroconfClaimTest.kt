package com.lightphone.spotify.playback.connect

import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Claiming a receiver, against a fake receiver that actually decrypts what it is sent.
 *
 * The server side here is a transcription of `librespot-discovery`'s `handle_add_user`: it hands out a
 * DH public key in `getInfo`, then opens the envelope, unwraps the credentials blob with *its own*
 * device id and checks it recovered the credential the phone holds. That is the part worth testing —
 * a claim can fail for a dozen reasons that all look identical from this side, and only the far end
 * can say the bytes were right.
 */
@RunWith(RobolectricTestRunner::class)
class ZeroconfClaimTest {

    private lateinit var server: MockWebServer
    private val receiverKeys = ZeroconfBlob.DhKeys.random(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) })
    private var received: ZeroconfBlob.DecodedBlob? = null
    private var receivedUsername: String? = null
    private var receivedTokenType: String? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a receiver recovers the credential the phone sent it`() {
        server.dispatcher = receiverDispatcher(infoPaths = setOf("/"))

        val outcome = claim()

        assertTrue("expected a claim, got $outcome", outcome is ZeroconfClaim.Outcome.Claimed)
        assertEquals(DEVICE_ID, (outcome as ZeroconfClaim.Outcome.Claimed).deviceId)
        assertEquals(USERNAME, receivedUsername)
        assertEquals(ZeroconfClaim.TOKEN_TYPE_DEFAULT, receivedTokenType)
        assertEquals(1, received?.authType)
        assertTrue(AUTH_DATA.contentEquals(received?.authData))
    }

    @Test
    fun `a receiver that only answers on zc is still claimed`() {
        // The ZeroConf path is not standardised, and a receiver found but not claimable because we
        // knocked on the wrong door would look exactly like a protocol failure.
        server.dispatcher = receiverDispatcher(infoPaths = setOf("/zc"))

        val outcome = claim()

        assertTrue("expected a claim, got $outcome", outcome is ZeroconfClaim.Outcome.Claimed)
        assertTrue(AUTH_DATA.contentEquals(received?.authData))
    }

    @Test
    fun `the advertised CPath is used, slash or no slash`() {
        // The spec puts the ZeroConf path in the mDNS TXT record precisely because it can be
        // anywhere, so a path outside the fallback list has to work — and the record is seen both
        // with and without a leading slash. Guessing instead of reading CPath is what made a
        // Cambridge receiver invisible.
        server.dispatcher = receiverDispatcher(infoPaths = setOf("/spotifyzc"))

        val outcome = runBlocking {
            ZeroconfClaim().claim(
                host = server.hostName,
                port = server.port,
                credentials = StoredCredentials(USERNAME, 1, AUTH_DATA),
                accessToken = null,
                controllerName = "LightPhono",
                controllerId = CONTROLLER_ID,
                preferredPath = "spotifyzc",
            )
        }

        assertTrue("expected a claim, got $outcome", outcome is ZeroconfClaim.Outcome.Claimed)
        assertTrue(AUTH_DATA.contentEquals(received?.authData))
    }

    @Test
    fun `a refusal is reported in the receiver's own words`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" -> MockResponse().setBody(infoJson())
                // What librespot answers when the HMAC does not check out. Passing it through beats
                // "could not connect", which is what the user used to get for every cause at once.
                else -> MockResponse().setBody(
                    """{"status":102,"spotifyError":1,"statusString":"ERROR-MAC"}""",
                )
            }
        }

        val outcome = claim()

        assertEquals(ZeroconfClaim.Outcome.Rejected("ERROR-MAC"), outcome)
    }

    @Test
    fun `no cached credential is a sign-in problem, not a network one`() {
        server.dispatcher = receiverDispatcher(infoPaths = setOf("/"))

        val outcome = runBlocking {
            ZeroconfClaim().claim(
                host = server.hostName,
                port = server.port,
                credentials = null,
                accessToken = null,
                controllerName = "LightPhono",
                controllerId = CONTROLLER_ID,
            )
        }

        assertTrue(outcome is ZeroconfClaim.Outcome.Failed)
        assertTrue((outcome as ZeroconfClaim.Outcome.Failed).message.contains("Sign in"))
    }

    @Test
    fun `a receiver with no public key is not attempted`() {
        // Nothing to seal against, so failing before the POST is the honest outcome — and it keeps a
        // stray HTTP server on the LAN from swallowing a credential.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBody("""{"status":101,"deviceID":"$DEVICE_ID","remoteName":"Amp"}""")
        }

        val outcome = claim()

        assertTrue(outcome is ZeroconfClaim.Outcome.Failed)
        // getInfo only. Nothing was posted, so no credential left the phone.
        assertTrue("posted to a receiver with no key", server.requestCount <= 2)
        assertEquals(null, received)
    }

    private fun claim(): ZeroconfClaim.Outcome = runBlocking {
        ZeroconfClaim().claim(
            host = server.hostName,
            port = server.port,
            credentials = StoredCredentials(USERNAME, 1, AUTH_DATA),
            accessToken = "an-access-token",
            controllerName = "LightPhono",
            controllerId = CONTROLLER_ID,
        )
    }

    /** The receiver half of the protocol. */
    private fun receiverDispatcher(infoPaths: Set<String>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath ?: return MockResponse().setResponseCode(404)
            if (request.method == "GET") {
                return if (path in infoPaths) {
                    MockResponse().setBody(infoJson())
                } else {
                    MockResponse().setResponseCode(404)
                }
            }
            val params = formParams(request.body.readUtf8())
            receivedUsername = params["userName"]
            receivedTokenType = params["tokenType"]
            val payload = ZeroconfBlob.open(
                blobBase64 = params.getValue("blob"),
                clientKeyBase64 = params.getValue("clientKey"),
                receiverKeys = receiverKeys,
            )
            // The envelope carries the *base64 text* of the blob, not its bytes — the nesting a
            // receiver would otherwise fail on with no explanation.
            received = ZeroconfBlob.decodeCredentialsBlob(
                blobBase64 = String(payload),
                username = params.getValue("userName"),
                deviceId = DEVICE_ID,
            )
            return MockResponse().setBody("""{"status":101,"spotifyError":0,"statusString":"OK"}""")
        }
    }

    private fun infoJson(): String = """
        {
          "status": 101,
          "statusString": "OK",
          "spotifyError": 0,
          "version": "2.9.0",
          "deviceID": "$DEVICE_ID",
          "deviceType": "SPEAKER",
          "remoteName": "Living Room",
          "publicKey": "${Base64.getEncoder().encodeToString(receiverKeys.publicKey)}",
          "brandDisplayName": "Denon",
          "modelDisplayName": "AVR-X",
          "tokenType": "default",
          "activeUser": ""
        }
    """.trimIndent()

    private fun formParams(body: String): Map<String, String> = body.split("&")
        .mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) {
                null
            } else {
                URLDecoder.decode(pair.substring(0, i), "UTF-8") to
                    URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }
        }
        .toMap()

    private companion object {
        const val DEVICE_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678"
        const val USERNAME = "someaccount"
        const val CONTROLLER_ID = "ffeeddccbbaa99887766554433221100ffeeddcc"
        val AUTH_DATA = ByteArray(211) { (it * 5 + 1).toByte() }
    }
}
