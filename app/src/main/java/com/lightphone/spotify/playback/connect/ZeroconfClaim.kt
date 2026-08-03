package com.lightphone.spotify.playback.connect

import android.util.Log
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * What a receiver says about itself in answer to `action=getInfo`.
 *
 * [path] is kept because the ZeroConf path is not standardised — most receivers answer on `/` and
 * some on `/zc` — and `addUser` has to go to whichever one replied.
 */
internal data class ZeroconfInfo(
    val path: String,
    val deviceId: String?,
    val brand: String?,
    val model: String?,
    val remoteName: String?,
    /** Non-empty when somebody is already logged in; such a receiver is usually visible to the Web API. */
    val activeUser: String?,
    /** The receiver's DH public key, base64, still encoded. */
    val publicKey: String?,
    /** `default` for a credentials blob, `accesstoken` for the newer flow. */
    val tokenType: String,
) {
    val displayName: String
        get() = listOfNotNull(brand, model).joinToString(" ")
            .ifBlank { remoteName.orEmpty() }
            // Ends up in a user-facing sentence, so it cannot be empty.
            .ifBlank { "That device" }
}

/**
 * Claims a Spotify Connect receiver for the signed-in account — the half `ZeroconfDiscovery` used to
 * leave undone.
 *
 * ### Why claiming is what makes a LAN receiver usable
 * `GET /me/player/devices` lists only devices *registered to the account*. A receiver on the network
 * has announced itself over mDNS but told Spotify nothing, so the Web API cannot see it and playback
 * cannot be transferred to it. `addUser` is how a controller logs the account *into* the receiver; the
 * receiver then opens its own session with Spotify and shows up in the device list within a few
 * seconds, at which point the ordinary transfer path works. This is exactly what happens when you tap
 * a speaker in the desktop app — which is why "start it once from Spotify" used to be the workaround.
 *
 * ### Two flows, chosen by the receiver
 * `getInfo`'s `tokenType` says which payload the receiver expects inside the envelope: `default`
 * means a credentials blob sealed for its own device id, `accesstoken` means a bare OAuth access
 * token. Both are attempted — the advertised one first — because the field is known to be wrong or
 * missing on real hardware, and a rejected claim is cheap while a receiver the user cannot select is
 * not.
 */
internal class ZeroconfClaim(
    private val http: OkHttpClient = defaultClient(),
    private val random: SecureRandom = SecureRandom(),
) {

    sealed interface Outcome {
        /** The receiver accepted the login. [deviceId] is what to look for in the Web API list. */
        data class Claimed(val deviceId: String?) : Outcome

        /** The receiver answered, and said no. [message] is its own words. */
        data class Rejected(val message: String) : Outcome

        /** Never got a usable answer — offline, wrong path, no credentials to send. */
        data class Failed(val message: String) : Outcome
    }

    suspend fun claim(
        host: String,
        port: Int,
        credentials: StoredCredentials?,
        accessToken: String?,
        controllerName: String,
        controllerId: String,
        /** The receiver's advertised `CPath`, when mDNS gave one. */
        preferredPath: String? = null,
    ): Outcome = withContext(Dispatchers.IO) {
        // Re-read getInfo rather than trusting what discovery cached: the public key belongs to the
        // receiver's current process, and a speaker that rebooted since the browse would reject a
        // blob sealed against the old one.
        val info = fetchInfo(http, host, port, preferredPath)
            ?: return@withContext Outcome.Failed("$host did not answer as a Spotify device")
        val publicKey = info.publicKey?.let { runCatching { decodeBase64(it) }.getOrNull() }
            ?: return@withContext Outcome.Failed("${info.displayName} sent no usable key")
        val deviceId = info.deviceId
            ?: return@withContext Outcome.Failed("${info.displayName} sent no device id")

        val attempts = attemptOrder(info.tokenType)
        var lastRejection: String? = null
        for (tokenType in attempts) {
            val payload = when (tokenType) {
                TOKEN_TYPE_ACCESS -> accessToken?.toByteArray()
                else -> credentials?.let {
                    ZeroconfBlob.encodeCredentialsBlob(
                        username = it.username,
                        authType = it.authType,
                        authData = it.authData,
                        deviceId = deviceId,
                    ).toByteArray()
                }
            }
            val username = credentials?.username
            if (payload == null || username == null) continue

            val sealed = ZeroconfBlob.seal(payload, publicKey, random)
            when (val result = post(host, port, info.path, tokenType, username, sealed, controllerName, controllerId)) {
                is Outcome.Claimed -> return@withContext Outcome.Claimed(deviceId)
                is Outcome.Rejected -> lastRejection = result.message
                is Outcome.Failed -> return@withContext result
            }
        }
        when {
            lastRejection != null -> Outcome.Rejected(lastRejection)
            credentials == null ->
                Outcome.Failed("Sign in to Spotify on the phone first — a speaker is handed this phone's login")
            else -> Outcome.Failed("${info.displayName} would not accept a login")
        }
    }

    /**
     * The credentials blob first, whatever the receiver advertises.
     *
     * This used to lead with whatever `tokenType` said, which meant an `accesstoken` receiver got a
     * bare access token first. That is the wrong reading of the field: the author of SpotifyPlus,
     * who has this working against Bose, Onkyo and Yamaha — the same eSDK family as a Cambridge
     * CXN100 — reports that those devices take a credentials blob too. The token attempt is kept as a
     * second try, since it costs one POST and some device out there may want it.
     */
    private fun attemptOrder(tokenType: String): List<String> =
        listOf(TOKEN_TYPE_DEFAULT, TOKEN_TYPE_ACCESS)

    private fun post(
        host: String,
        port: Int,
        path: String,
        tokenType: String,
        username: String,
        sealed: ZeroconfBlob.Sealed,
        controllerName: String,
        controllerId: String,
    ): Outcome {
        // `action` goes in the query *and* the body: librespot merges both and takes the body last,
        // while some receivers only route on the query string.
        val url = "http://${urlHost(host)}:$port$path?action=addUser"
        val form = FormBody.Builder()
            .add("action", "addUser")
            .add("userName", username)
            .add("blob", sealed.blob)
            .add("clientKey", sealed.clientKey)
            .add("deviceName", controllerName)
            .add("deviceId", controllerId)
            .add("tokenType", tokenType)
            // Sent by every real client; the desktop app's is 32 hex characters. Its meaning is not
            // published and librespot ignores it, but a receiver that expects the field is cheaper to
            // satisfy than to diagnose.
            .add("loginId", loginId(username, controllerId))
            .add("version", ZEROCONF_VERSION)
            .build()

        // Read the body whatever the status code says. A Cambridge CXN100 answers a refused login
        // with HTTP 500 and a perfectly good `{"statusString":"ERROR-SPOTIFY-ERROR"}`; treating the
        // status as the answer threw away the only part that names the problem.
        val (code, body) = runCatching {
            http.newCall(Request.Builder().url(url).post(form).build()).execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        }.getOrElse { e ->
            Log.w(TAG, "addUser to $host:$port failed", e)
            return Outcome.Failed("Could not reach the speaker")
        }

        Log.i(TAG, "addUser to $host:$port ($tokenType) HTTP $code -> $body")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return Outcome.Rejected("Speaker refused the login (HTTP $code)")
        // 101 is the protocol's OK. Everything else carries a statusString worth showing, because
        // the useful ones name the actual problem: ERROR-MAC, ERROR-INVALID-PUBLICKEY,
        // ERROR-SPOTIFY-ERROR for a login the receiver could not complete.
        return if (json.optInt("status", -1) == STATUS_OK && json.optInt("spotifyError", 0) == 0) {
            Outcome.Claimed(null)
        } else {
            val reason = json.optString("statusString").takeIf { it.isNotBlank() }
                ?: "status ${json.optInt("status", -1)}"
            Outcome.Rejected(reason)
        }
    }

    internal companion object {
        private const val TAG = "ZeroconfClaim"
        private const val STATUS_OK = 101
        const val TOKEN_TYPE_DEFAULT = "default"
        const val TOKEN_TYPE_ACCESS = "accesstoken"

        /**
         * Sent as `version` on both requests. Receivers use it to decide how to talk back, and a
         * value they have never heard of is the sort of thing that gets a request dropped. This is
         * what the Spotify desktop client was observed sending.
         */
        const val ZEROCONF_VERSION = "2.12.0"

        /**
         * Fallbacks for a receiver whose TXT record carried no `CPath`. Not a guess at the spec —
         * the spec says read `CPath` — just the values real hardware is known to use, tried in the
         * order they turn up in the wild.
         */
        private val INFO_PATHS = listOf(
            "/",
            "/zc",
            "/zc/0",
            "/zeroconf",
            "/CSpotifyConnect",
            // Observed on Gio's own network, which is the whole reason CPath is read at all:
            // Cambridge Audio CXN100, Sony STR-DN1080, PS5.
            "/spotify_zeroconf",
            "/goform/spotifyConnect",
            "/spConn",
        )

        /**
         * Separate from discovery's client: `getInfo` either answers at once or is not there, but
         * `addUser` makes the receiver open its own session with Spotify before it replies, and a 3s
         * read timeout turns that into a spurious failure.
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        /**
         * 32 hex characters, stable for this account on this phone, matching the shape the desktop
         * client sends. Derived rather than random so a receiver that keys anything on it sees the
         * same value every time.
         */
        private fun loginId(username: String, controllerId: String): String =
            ZeroconfBlob.sha1((username + controllerId).toByteArray())
                .take(16)
                .joinToString("") { "%02x".format(it) }

        /**
         * A stable 40-hex id for this phone as a *controller*.
         *
         * librespot ignores the field, but real receivers log it and some key their "recent
         * controllers" list on it, so it must not change between claims. Derived from ANDROID_ID
         * rather than generated and stored, so there is no extra file to keep in sync — and hashed,
         * so the raw hardware id never leaves the phone.
         */
        fun controllerId(context: android.content.Context): String {
            val androidId = runCatching {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID,
                )
            }.getOrNull().orEmpty()
            val digest = ZeroconfBlob.sha1((androidId + context.packageName).toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        /**
         * An IPv6 literal has to be bracketed in a URL, and its scope id (`%wlan0`) is not valid in
         * one at all. mDNS answers with both an A and an AAAA record, so a bare v6 address here would
         * fail to parse and take the receiver down with it.
         */
        private fun urlHost(host: String): String = when {
            !host.contains(':') -> host
            else -> "[" + host.substringBefore('%') + "]"
        }

        /** A CPath may or may not carry its leading slash; both are seen. */
        private fun String.normalisedPath(): String = if (startsWith("/")) this else "/$this"

        private fun decodeBase64(text: String): ByteArray =
            java.util.Base64.getDecoder().decode(text.trim())

        /**
         * Ask a receiver who it is. Tries both known paths and returns the first that answers with
         * JSON; null means it is not a Spotify Connect endpoint (or is not there any more).
         *
         * Shared with [ZeroconfDiscovery] so discovery and claiming can never disagree about what a
         * receiver said.
         */
        fun fetchInfo(
            http: OkHttpClient,
            host: String,
            port: Int,
            preferredPath: String? = null,
        ): ZeroconfInfo? {
            // The TXT record's CPath is authoritative, so it goes first; the rest is only for a
            // receiver that advertised none.
            val paths = (listOfNotNull(preferredPath?.normalisedPath()) + INFO_PATHS).distinct()
            for (path in paths) {
                val url = "http://${urlHost(host)}:$port$path?action=getInfo&version=$ZEROCONF_VERSION"
                val body = runCatching {
                    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) null else resp.body?.string()
                    }
                    // Logged rather than swallowed. Every one of these failed for a year with
                    // "CLEARTEXT communication not permitted", and a silent `getOrNull` turned a
                    // policy that blocked the request outright into "the speaker did not answer".
                }.onFailure { Log.i(TAG, "getInfo $url failed: $it") }.getOrNull() ?: continue
                val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
                Log.i(TAG, "getInfo ok at $url: $body")
                return ZeroconfInfo(
                    path = path,
                    deviceId = json.optString("deviceID").takeIf { it.isNotBlank() },
                    brand = json.optString("brandDisplayName").takeIf { it.isNotBlank() },
                    model = json.optString("modelDisplayName").takeIf { it.isNotBlank() },
                    remoteName = json.optString("remoteName").takeIf { it.isNotBlank() },
                    activeUser = json.optString("activeUser").takeIf { it.isNotBlank() },
                    publicKey = json.optString("publicKey").takeIf { it.isNotBlank() },
                    tokenType = json.optString("tokenType").takeIf { it.isNotBlank() }
                        ?: TOKEN_TYPE_DEFAULT,
                )
            }
            return null
        }
    }
}
