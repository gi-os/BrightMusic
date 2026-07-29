package com.lightphone.spotify.audio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Connects a paired Bluetooth audio device from inside the app.
 *
 * ### Why this is awkward
 * Android has no public API for it. `BluetoothA2dp.connect()` exists and does exactly what is wanted,
 * but it is `@hide` and requires `BLUETOOTH_PRIVILEGED`, which is `signature|privileged` — so unlike
 * `WRITE_SECURE_SETTINGS` it cannot be granted over adb either. On a strict build the reflective call
 * is refused and no amount of app-side cleverness changes that.
 *
 * ### So it tries three things in order and reports which one it got
 *  1. **Reflective `connect` on the A2DP proxy**, obtained through the *public*
 *     `BluetoothAdapter.getProfileProxy`. Succeeds on builds that do not enforce
 *     `BLUETOOTH_PRIVILEGED` on this path, and on any build where the hidden-API policy is relaxed.
 *     One call to find out.
 *  2. **`createBond()`** when the device is not actually bonded. Pairing brings audio up with it, and
 *     this path is public and permitted.
 *  3. **`fetchUuidsWithSdp()`** as a nudge: an SDP query opens an ACL link, and Android's own A2DP
 *     state machine very often connects a bonded audio device once it is reachable. Reported as
 *     [Result.Nudged] rather than success, because it is a prod and not a promise.
 *
 * Even a success here means *accepted*, not *connected* — the stack finishes asynchronously, which is
 * what [awaitConnected] is for.
 *
 * If step 1 is refused on this phone, one adb command opens it up:
 *
 *     adb shell settings put global hidden_api_policy 1
 */
object BluetoothConnector {
    private const val TAG = "BluetoothConnector"

    sealed interface Result {
        /** A2DP accepted the request; audio should follow within a second or two. */
        data object Requested : Result

        /** Not bonded, so pairing was started. The user may get a system pairing prompt. */
        data object Pairing : Result

        /** Fallback taken: the device was woken and the system may connect it itself. */
        data object Nudged : Result

        /** Nothing worked, with the reason the platform gave. */
        data class Refused(val reason: String) : Result
    }

    suspend fun connect(context: Context, address: String): Result = withContext(Dispatchers.IO) {
        val adapter = adapterOf(context)
            ?: return@withContext Result.Refused("No Bluetooth adapter")
        if (!adapter.isEnabled) return@withContext Result.Refused("Bluetooth is off")

        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: return@withContext Result.Refused("Unknown device")

        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        if (!bonded) {
            val started = runCatching { device.createBond() }.getOrDefault(false)
            return@withContext if (started) {
                Result.Pairing
            } else {
                Result.Refused("Could not start pairing")
            }
        }

        val viaProfile = withA2dpProxy(context) { proxy -> reflectConnect(proxy, device) }
        if (viaProfile is Result.Requested) return@withContext viaProfile
        if (viaProfile is Result.Refused) Log.i(TAG, "A2DP connect unavailable: ${viaProfile.reason}")

        // Last resort: wake it and let the system's own state machine take over.
        val poked = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
        if (poked) {
            Result.Nudged
        } else {
            Result.Refused("Android will not let an app connect this device")
        }
    }

    /**
     * Wait until [address] appears as a connected A2DP device, so the UI reflects reality rather than
     * the request. False on timeout, which is a perfectly normal outcome for headphones still in a case.
     *
     * [BluetoothProfile.getConnectedDevices] is public, unlike `connect`.
     */
    suspend fun awaitConnected(
        context: Context,
        address: String,
        timeoutMs: Long = CONNECT_TIMEOUT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isA2dpConnected(context, address)) return true
            delay(POLL_MS)
        }
        return false
    }

    suspend fun isA2dpConnected(context: Context, address: String): Boolean =
        withA2dpProxy(context) { proxy ->
            runCatching {
                proxy.connectedDevices?.any { it.address.equals(address, ignoreCase = true) } == true
            }.getOrDefault(false)
        } ?: false

    private fun reflectConnect(proxy: BluetoothProfile, device: BluetoothDevice): Result = try {
        // Reflection is confined to this one method so a failure stays legible in a stack trace.
        val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
        method.isAccessible = true
        if (method.invoke(proxy, device) as? Boolean == true) {
            Result.Requested
        } else {
            Result.Refused("A2DP refused the device")
        }
    } catch (e: NoSuchMethodException) {
        // Hidden-API filtering hides the method rather than throwing when it is called.
        Result.Refused("connect() is hidden on this build")
    } catch (e: Exception) {
        // Usually SecurityException for BLUETOOTH_PRIVILEGED — the expected outcome on a strict build,
        // so it is logged at info rather than as a fault.
        Log.i(TAG, "reflective A2DP connect rejected: ${e.javaClass.simpleName}")
        Result.Refused(e.cause?.message ?: e.message ?: "Rejected by the system")
    }

    /**
     * Runs [block] with a live A2DP proxy and always closes it.
     *
     * The proxy arrives on a callback, so this is the awkward bit both callers would otherwise
     * duplicate. Null when the profile never turned up.
     */
    private suspend fun <T> withA2dpProxy(context: Context, block: (BluetoothProfile) -> T): T? {
        val adapter = adapterOf(context) ?: return null
        val proxy = withTimeoutOrNull(PROXY_TIMEOUT_MS) {
            suspendCancellableCoroutine<BluetoothProfile?> { cont ->
                val listener = object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
                        if (cont.isActive) cont.resume(p)
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                val requested = runCatching {
                    adapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)
                }.getOrDefault(false)
                if (!requested && cont.isActive) cont.resume(null)
            }
        } ?: return null

        return try {
            block(proxy)
        } finally {
            runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
        }
    }

    private fun adapterOf(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    private const val PROXY_TIMEOUT_MS = 3_000L
    private const val CONNECT_TIMEOUT_MS = 12_000L
    private const val POLL_MS = 500L
}
