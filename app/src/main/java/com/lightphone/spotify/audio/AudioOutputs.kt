package com.lightphone.spotify.audio

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Where LightPhono's audio can go, and where it is going.
 *
 * ### Why this exists instead of a settings shortcut
 * The first version of the Bluetooth button fired `Settings.ACTION_BLUETOOTH_SETTINGS`, which does not
 * resolve on LightOS — LightOS runs its own settings surfaces and does not declare the AOSP filters.
 * So the app owns the screen now.
 *
 * ### What Android actually permits
 * There is no public API to *connect* a Bluetooth device. `BluetoothA2dp.connect()` is `@hide`, and
 * `BLUETOOTH_PRIVILEGED` is `signature|privileged` so it cannot even be granted over adb the way
 * `WRITE_SECURE_SETTINGS` can. What *is* public, and what a music player actually needs, is
 * [android.media.AudioTrack.setPreferredDevice] — choosing among outputs that are already connected.
 *
 * So this reports two different things and the UI must not conflate them:
 *
 *  - **[connected]** — live audio outputs from [AudioManager]. These can be selected, and switching is
 *    real and immediate.
 *  - **[pairedNotConnected]** — bonded Bluetooth devices with no live output. Listed so the screen
 *    explains where your headphones went, but they cannot be woken from here.
 */
object AudioOutputs {
    private const val TAG = "AudioOutputs"

    data class Output(
        /** [AudioDeviceInfo.getId], stable only while the device stays connected. */
        val id: Int,
        val name: String,
        val kind: Kind,
        /** Null for the paired-but-idle entries, which have no live route to select. */
        val device: AudioDeviceInfo?,
    )

    enum class Kind { PHONE, BLUETOOTH, WIRED, OTHER }

    data class Snapshot(
        val connected: List<Output> = emptyList(),
        val pairedNotConnected: List<String> = emptyList(),
        /** Null when Android is choosing, otherwise the id this app asked for. */
        val preferredId: Int? = null,
        /** Where audio is actually coming out, which can lag a request or ignore it. */
        val routedId: Int? = null,
        val bluetoothOn: Boolean = false,
        /** False when BLUETOOTH_CONNECT is missing, in which case paired names are unreadable. */
        val canReadPaired: Boolean = true,
    )

    fun snapshot(context: Context, preferredId: Int?): Snapshot {
        val am = context.getSystemService(AudioManager::class.java)
        val connected = am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.filter { it.isSink }
            // Telephony and unknown sinks are not somewhere you play an album.
            ?.filter { kindOf(it.type) != Kind.OTHER }
            ?.map {
                Output(
                    id = it.id,
                    name = outputName(it),
                    kind = kindOf(it.type),
                    device = it,
                )
            }
            ?.distinctBy { it.id }
            .orEmpty()

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val canRead = hasBluetoothConnect(context)
        val connectedBtNames = connected.filter { it.kind == Kind.BLUETOOTH }.map { it.name }
        val paired = if (adapter != null && canRead) {
            runCatching {
                adapter.bondedDevices
                    ?.filter { it.isAudioDevice() }
                    ?.map { it.friendlyName() }
                    ?.filterNot { name -> connectedBtNames.any { it.equals(name, true) } }
                    ?.sorted()
                    .orEmpty()
            }.getOrElse {
                // Some OEM stacks throw here even with the permission held.
                Log.w(TAG, "bondedDevices unavailable", it)
                emptyList()
            }
        } else {
            emptyList()
        }

        return Snapshot(
            connected = connected,
            pairedNotConnected = paired,
            preferredId = preferredId,
            routedId = PhonoAudioTrackSink.currentRoutedDeviceId(),
            bluetoothOn = adapter?.isEnabled == true,
            canReadPaired = canRead,
        )
    }

    fun hasBluetoothConnect(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Bonded devices include keyboards, watches and phones. Only audio sinks belong on a "where does
     * the music go" screen, so filter on the Audio major device class rather than listing everything
     * the phone has ever met.
     */
    private fun BluetoothDevice.isAudioDevice(): Boolean {
        val major = runCatching { bluetoothClass?.majorDeviceClass }.getOrNull() ?: return true
        return major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    private fun BluetoothDevice.friendlyName(): String =
        runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address

    private fun outputName(info: AudioDeviceInfo): String {
        val label = runCatching { info.productName?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        return when (kindOf(info.type)) {
            // productName for the built-in speaker is the phone model, which reads oddly in a list of
            // outputs — "Light Phone III" next to "Light Phone III" tells you nothing.
            Kind.PHONE -> "Phone speaker"
            Kind.WIRED -> label ?: "Headphones"
            Kind.BLUETOOTH -> label ?: "Bluetooth"
            Kind.OTHER -> label ?: "Output"
        }
    }

    private fun kindOf(type: Int): Kind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> Kind.PHONE
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        -> Kind.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        -> Kind.BLUETOOTH
        else -> Kind.OTHER
    }

    /** Adapter state, for the "Bluetooth is off" hint. Enabling it is not something an app may do. */
    fun bluetoothAdapterPresent(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter != null
}
