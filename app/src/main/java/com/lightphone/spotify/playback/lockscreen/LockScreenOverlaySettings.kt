package com.lightphone.spotify.playback.lockscreen

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lightphone.spotify.playback.PlaybackResume

/**
 * Whether the lock-screen overlay is switched on, kept beside the other playback preferences.
 *
 * Same file as the resume point and the fade length (`phono_playback`), which is already in the
 * backup's Settings store, so this travels to a restored phone without touching that code.
 */
class LockScreenOverlayPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PlaybackResume.PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private companion object {
        const val KEY_ENABLED = "lock_screen_overlay_enabled"
    }
}

/**
 * The live value, read by the settings screen and by the overlay itself.
 *
 * Defaults to on, because it does nothing at all without the `SYSTEM_ALERT_WINDOW` appop: a build
 * that has not been granted it behaves exactly as before, and granting it is the act of opting in.
 */
object LockScreenOverlaySettings {
    var enabled: Boolean by mutableStateOf(true)
        private set

    /**
     * Set by the playback service so flipping the toggle takes the row away at once.
     *
     * A single slot, not a list: there is one overlay, it lives as long as the service, and the
     * service clears this in `onDestroy`.
     */
    var onChanged: (() -> Unit)? = null

    fun load(prefs: LockScreenOverlayPreferences) {
        enabled = prefs.enabled()
    }

    fun set(prefs: LockScreenOverlayPreferences, value: Boolean) {
        enabled = value
        prefs.setEnabled(value)
        onChanged?.invoke()
    }

    /**
     * Whether the app may draw over other apps.
     *
     * LightOS has no Settings UI for this — `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` is the same
     * class of intent as `ACTION_BLUETOOTH_SETTINGS`, which does not resolve on this phone — so it is
     * an adb grant:
     *
     * ```
     * adb shell appops set com.lightphone.spotify SYSTEM_ALERT_WINDOW allow
     * ```
     *
     * Re-read on every decision rather than cached: an appop can be revoked while the app runs, and
     * `addView` on a revoked one throws rather than returning anything.
     */
    fun canDrawOverlays(context: Context): Boolean =
        runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
}
