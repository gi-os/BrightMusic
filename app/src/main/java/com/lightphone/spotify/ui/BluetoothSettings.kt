package com.lightphone.spotify.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Opens the phone's Bluetooth screen, for pairing headphones or a speaker.
 *
 * **There is no Bluetooth API in the Light SDK.** The SDK ships an icon
 * (`LightIcons.BLUETOOTH`, `ic_bluetooth_*.xml`) and nothing else — no manager, no screen,
 * no navigation target. Checked against `lightphone/light-sdk` at 485 files: the only
 * Bluetooth-shaped things in it are those two drawables. So this is a plain Android
 * `Settings` intent into whatever LightOS puts behind it, not an SDK call.
 *
 * LightOS runs its own launcher and settings surfaces, and there is no guarantee it
 * declares the AOSP intent filters. Rather than assume, every candidate is resolved
 * against the PackageManager before it is offered, and [isAvailable] lets the UI hide the
 * control entirely if none of them resolve — better than a button that does nothing.
 *
 * If the generic action turns out not to resolve on a real device, find what does with:
 *
 *     adb shell dumpsys package | grep -i -A3 'bluetooth.*Activity'
 *     adb shell cmd package resolve-activity -a android.settings.BLUETOOTH_SETTINGS
 *
 * and add the concrete component to [CANDIDATES] ahead of the generic actions.
 */
object BluetoothSettings {
    private const val TAG = "BluetoothSettings"

    /**
     * Tried in order, most specific first. The last entry is the top-level Settings screen:
     * two taps instead of one, but it is the action least likely to be missing.
     */
    private val CANDIDATES: List<Intent>
        get() = listOf(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )

    /** True when something on this device will actually handle one of [CANDIDATES]. */
    fun isAvailable(context: Context): Boolean = resolve(context) != null

    private fun resolve(context: Context): Intent? {
        val pm = context.packageManager
        return CANDIDATES.firstOrNull { intent ->
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L)) != null
        }
    }

    /** Returns false if nothing could be launched, so callers can say so rather than no-op. */
    fun open(context: Context): Boolean {
        val intent = resolve(context) ?: run {
            Log.w(TAG, "no activity resolves any Bluetooth settings intent on this device")
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // resolveActivity said yes and startActivity still failed — a locked-down
            // LightOS build can do this.
            Log.w(TAG, "failed to open ${intent.action}", e)
            false
        }
    }
}
