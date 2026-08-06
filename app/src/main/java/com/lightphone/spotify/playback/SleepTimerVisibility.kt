package com.lightphone.spotify.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether Now Playing offers the sleep timer at all.
 *
 * Off by default: the line was there unconditionally, and on a screen this small a row nobody uses is
 * a row in the way. Turning it on in Settings puts it back.
 *
 * Rides in `phono_playback` with the resume point and the fade length, so it travels with a backup
 * without touching [com.lightphone.spotify.backup.Backup].
 */
class SleepTimerVisibilityPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PlaybackResume.PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private companion object {
        const val KEY_ENABLED = "sleep_timer_line_visible"
    }
}

/** The live value, read directly by Now Playing and by the settings screen. */
object SleepTimerVisibility {
    var enabled: Boolean by mutableStateOf(false)
        private set

    fun load(prefs: SleepTimerVisibilityPreferences) {
        enabled = prefs.enabled()
    }

    fun set(prefs: SleepTimerVisibilityPreferences, value: Boolean) {
        enabled = value
        prefs.setEnabled(value)
    }

    /**
     * Whether the line should be on screen.
     *
     * A running timer always shows, whatever the setting says. Hiding one that is already counting
     * down would leave the user with no way to see it or cancel it, and switching the row off is a
     * statement about clutter, not a request to be put to sleep silently.
     */
    fun shouldShowLine(armed: Boolean): Boolean = enabled || armed
}
