package com.lightphone.spotify.radio.recognize

import android.content.Context

/**
 * The ShazamKit developer token, scanned in by QR and kept in prefs.
 *
 * It is a JWT signed with the Media Services key from Gio's Apple Developer account, and Apple
 * caps its life at three months — so it is a thing that gets *rescanned*, not configured once.
 * The settings screen reads the expiry straight out of the token so the rescan is prompted, not
 * discovered when recognition quietly stops working.
 */
object ShazamToken {

    private const val PREF = "song_recognition"
    private const val KEY_TOKEN = "developer_token"

    fun load(context: Context): String? =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
            ?.takeUnless { it.isBlank() }

    fun save(context: Context, token: String) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TOKEN)
            .apply()
    }
}

/**
 * Parsing for the token QR, split from the prefs store so it is pure and testable.
 *
 * Two shapes are accepted: the raw JWT itself (paste the signer's output straight into a QR
 * generator, nothing else needed), or `{"type":"shazam","token":"<jwt>"}` for symmetry with the
 * bridge and Web API QR payloads.
 */
object ShazamTokenPayload {

    /** The token out of whatever was scanned, or null if this QR is not ours. */
    fun parse(raw: String): String? {
        val trimmed = raw.trim()
        if (JWT_SHAPE.matches(trimmed)) return trimmed
        if (!TYPE_FIELD.containsMatchIn(trimmed)) return null
        val token = TOKEN_FIELD.find(trimmed)?.groupValues?.get(1) ?: return null
        return token.takeIf { JWT_SHAPE.matches(it) }
    }

    /**
     * The `exp` claim, in epoch seconds — read by decoding the JWT payload locally rather than
     * asking a server. Null when the token has no readable expiry; that is worth showing as its
     * own state rather than pretending a date.
     */
    fun expiryEpochSeconds(jwt: String): Long? = runCatching {
        val payload = jwt.split(".").getOrNull(1) ?: return@runCatching null
        val json = String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        EXP_FIELD.find(json)?.groupValues?.get(1)?.toLong()
    }.getOrNull()

    fun isExpired(jwt: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val exp = expiryEpochSeconds(jwt) ?: return false
        return exp * 1000 < nowMs
    }

    private val JWT_SHAPE = Regex("""^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$""")
    private val TYPE_FIELD = Regex(""""type"\s*:\s*"shazam"""")
    private val TOKEN_FIELD = Regex(""""token"\s*:\s*"([^"]+)"""")
    private val EXP_FIELD = Regex(""""exp"\s*:\s*(\d+)""")
}
