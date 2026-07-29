package com.lightphone.spotify.data.backend

import android.content.Context

/**
 * The streaming backend an install is bound to.
 *
 * LightPhono dropped upstream phono's TIDAL backend, so there is one value and no first-run
 * picker. The enum survives because `PlaybackController`, `BackendCapabilities` and the
 * repositories are all written against it, and collapsing it would make every future merge
 * from upstream a conflict.
 */
enum class BackendChoice {
    SPOTIFY,
    ;

    companion object {
        fun fromKey(key: String?): BackendChoice? = when (key) {
            SPOTIFY.name -> SPOTIFY
            else -> null
        }
    }
}

/**
 * Tiny non-encrypted prefs store for the single-active-backend selection. This is
 * a benign UI routing flag, not a secret, so plain [android.content.SharedPreferences]
 * is fine (tokens live in each backend's own EncryptedSharedPreferences).
 */
class BackendPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun choice(): BackendChoice? = BackendChoice.fromKey(prefs.getString(KEY_CHOICE, null))

    fun isChosen(): Boolean = choice() != null

    /**
     * Pin the only backend there is. Also rewrites a stored `TIDAL` from an upstream phono
     * install, which would otherwise read back as null and leave the app with no choice at
     * all now that the picker is gone.
     */
    fun ensureSpotify() {
        if (choice() != BackendChoice.SPOTIFY) setChoice(BackendChoice.SPOTIFY)
    }

    fun setChoice(choice: BackendChoice) {
        prefs.edit().putString(KEY_CHOICE, choice.name).commit()
    }

    fun clear() {
        prefs.edit().remove(KEY_CHOICE).commit()
    }

    companion object {
        private const val PREFS_NAME = "phono_backend_choice"
        private const val KEY_CHOICE = "choice"
    }
}
