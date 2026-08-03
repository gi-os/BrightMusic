package com.lightphone.spotify.playback.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Settings for [LibraryAutoDownload], in SharedPreferences.
 *
 * Not Room, for the same reason podcast settings are not: `PhonoDatabase` uses
 * `fallbackToDestructiveMigration()`, so a new entity means a version bump that deletes every
 * download on the phone. Two booleans and two integers are not worth that.
 *
 * Its own preferences file rather than the podcast one, because these outlive any interest in
 * podcasts and mixing them makes "clear podcast settings" a dangerous phrase later.
 */
object LibraryAutoDownloadSettings {

    var likedEnabled: Boolean by mutableStateOf(false)
        private set

    var likedLimit: Int by mutableStateOf(AutoPinPlan.DEFAULT_LIKED_LIMIT)
        private set

    var mixesEnabled: Boolean by mutableStateOf(false)
        private set

    fun load(prefs: LibraryAutoDownloadPreferences) {
        likedEnabled = prefs.likedEnabled()
        likedLimit = prefs.likedLimit()
        mixesEnabled = prefs.mixesEnabled()
    }

    fun setLikedEnabled(prefs: LibraryAutoDownloadPreferences, value: Boolean) {
        likedEnabled = value
        prefs.setLikedEnabled(value)
    }

    fun setLikedLimit(prefs: LibraryAutoDownloadPreferences, value: Int) {
        likedLimit = value
        prefs.setLikedLimit(value)
    }

    fun setMixesEnabled(prefs: LibraryAutoDownloadPreferences, value: Boolean) {
        mixesEnabled = value
        prefs.setMixesEnabled(value)
    }
}

class LibraryAutoDownloadPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun likedEnabled(): Boolean = prefs.getBoolean(KEY_LIKED, false)

    fun setLikedEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_LIKED, value).apply()
    }

    /**
     * How many of the newest liked tracks to keep.
     *
     * Read back through the choice list rather than trusted as written, so a number from a build
     * that offered a different set of options cannot turn into an unbounded download.
     */
    fun likedLimit(): Int {
        val stored = prefs.getInt(KEY_LIKED_LIMIT, AutoPinPlan.DEFAULT_LIKED_LIMIT)
        return if (stored in AutoPinPlan.LIKED_LIMIT_CHOICES) {
            stored
        } else {
            AutoPinPlan.DEFAULT_LIKED_LIMIT
        }
    }

    fun setLikedLimit(value: Int) {
        prefs.edit().putInt(KEY_LIKED_LIMIT, value).apply()
    }

    fun mixesEnabled(): Boolean = prefs.getBoolean(KEY_MIXES, false)

    fun setMixesEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_MIXES, value).apply()
    }

    fun mixLimit(): Int = AutoPinPlan.DEFAULT_MIX_LIMIT

    fun lastCheckMs(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun setLastCheckMs(value: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK, value).apply()
    }

    private companion object {
        const val PREFS_NAME = "phono_library_auto_download"
        const val KEY_LIKED = "liked_enabled"
        const val KEY_LIKED_LIMIT = "liked_limit"
        const val KEY_MIXES = "mixes_enabled"
        const val KEY_LAST_CHECK = "last_check_ms"
    }
}
