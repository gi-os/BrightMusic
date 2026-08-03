package com.lightphone.spotify.playback.connect

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Base64
import org.json.JSONObject

/**
 * The account credential a Spotify Connect receiver can be handed, read off disk.
 *
 * ### Where this comes from
 * The Rust core logs in with an OAuth access token, and Spotify answers with a *reusable* credential
 * in the `APWelcome`. librespot writes that to `<cache>/creds/credentials.json` because
 * `Session::connect` is called with `store_credentials = true` — which is the same file it reads on
 * every later cold start, so it is guaranteed present for a signed-in user and guaranteed current.
 *
 * Reading the file rather than adding a UniFFI getter is deliberate: the format is librespot's own
 * `Credentials` serde shape and has to stay stable for its own cache to work, and this way claiming a
 * receiver needs no change to the Rust surface at all.
 *
 * A stored credential is not device-bound — it is exactly what the desktop client forwards to a
 * speaker — so passing it on is the intended use, not a workaround.
 */
internal data class StoredCredentials(
    /** Spotify's canonical username, which is what `addUser` must be told. */
    val username: String,
    /** librespot's `AuthenticationType` as an int; 1 is `STORED_SPOTIFY_CREDENTIALS`. */
    val authType: Int,
    val authData: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is StoredCredentials &&
        username == other.username && authType == other.authType &&
        authData.contentEquals(other.authData)

    override fun hashCode(): Int =
        31 * (31 * username.hashCode() + authType) + authData.contentHashCode()

    companion object {
        private const val TAG = "StoredCredentials"

        /**
         * `PlaybackEngineHolder` hands the engine `filesDir/spotify-cache`; librespot's own layout
         * puts the credential at `creds/credentials.json` inside it. Both halves are spelled out here
         * so moving the cache breaks in one obvious place rather than silently.
         */
        fun file(context: Context): File =
            File(context.filesDir, "spotify-cache/creds/credentials.json")

        /** Null when nobody is signed in, or when the file is not what we expect. */
        fun load(context: Context): StoredCredentials? {
            val file = file(context)
            if (!file.isFile) {
                Log.i(TAG, "no cached credentials at ${file.path}")
                return null
            }
            return runCatching {
                val json = JSONObject(file.readText())
                val username = json.optString("username").takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                val authData = json.optString("auth_data").takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                StoredCredentials(
                    username = username,
                    // Absent would mean a librespot format change, and guessing a type here would
                    // produce a blob the receiver rejects for no visible reason.
                    authType = json.optInt("auth_type", -1).takeIf { it >= 0 }
                        ?: return@runCatching null,
                    authData = Base64.getDecoder().decode(authData),
                )
            }.onFailure { Log.w(TAG, "cached credentials unreadable", it) }.getOrNull()
        }
    }
}
