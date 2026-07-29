package com.lightphone.spotify.playback.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Finds Spotify Connect receivers on the local network.
 *
 * ### Why this is needed at all
 * `GET /me/player/devices` only returns devices **registered to the account**. A receiver — an AV
 * amp, a Pi running librespot, a networked speaker — announces itself over mDNS as
 * `_spotify-connect._tcp` and is discovered by the desktop and mobile apps *locally*. Until one of
 * those apps logs the receiver into your account it is invisible to the Web API, which is exactly why
 * a receiver shows up everywhere except here. There is no Web API endpoint for LAN devices.
 *
 * ### What this does, and does not, do yet
 * This is the discovery half: browse for the service, resolve each one, and read its ZeroConf
 * `getInfo` so the UI can show what is out there and so the next step has real data to work against.
 *
 * It does **not** yet claim a receiver. Claiming is `action=addUser`, which needs a Diffie-Hellman
 * exchange against the `publicKey` from `getInfo` and a credentials blob encrypted with the shared
 * secret — that belongs in the Rust core, which already holds the librespot session and its crypto.
 * Until then a discovered receiver is listed as found-but-not-controllable, which is still strictly
 * better than the silence the Web API gives.
 *
 * ### Practicalities
 * mDNS on Android needs the app in the foreground and a real Wi-Fi link; `NsdManager` is also
 * famously flaky about concurrent discoveries, so [start] is idempotent and [stop] is always paired
 * with it from the screen's lifecycle.
 */
class ZeroconfDiscovery(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    data class Receiver(
        val name: String,
        val host: String,
        val port: Int,
        /** From getInfo. Null until resolved, or when the receiver refused the request. */
        val deviceId: String? = null,
        val brand: String? = null,
        val model: String? = null,
        /** True once getInfo answered, i.e. this really is a Spotify Connect endpoint. */
        val confirmed: Boolean = false,
        /** Set when getInfo says a user is already logged in — usually then visible to the Web API. */
        val activeUser: String? = null,
    )

    private val _receivers = MutableStateFlow<List<Receiver>>(emptyList())
    val receivers: StateFlow<List<Receiver>> = _receivers.asStateFlow()

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null

    private val http = OkHttpClient.Builder()
        // A receiver on the LAN either answers immediately or is not there; long timeouts just make
        // the screen feel broken.
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    fun start() {
        val manager = nsd ?: return
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {
                Log.i(TAG, "mDNS browse started for $serviceType")
            }

            override fun onServiceFound(info: NsdServiceInfo?) {
                val svc = info ?: return
                // Resolve is a separate round trip: onServiceFound carries only the name.
                @Suppress("DEPRECATION")
                manager.resolveService(
                    svc,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo?, errorCode: Int) {
                            Log.w(TAG, "resolve failed for ${svc.serviceName}: $errorCode")
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo?) {
                            val r = resolved ?: return
                            @Suppress("DEPRECATION")
                            val host = r.host?.hostAddress ?: return
                            add(Receiver(name = r.serviceName ?: host, host = host, port = r.port))
                        }
                    },
                )
            }

            override fun onServiceLost(info: NsdServiceInfo?) {
                val name = info?.serviceName ?: return
                _receivers.value = _receivers.value.filterNot { it.name == name }
            }

            override fun onDiscoveryStopped(serviceType: String?) {}

            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "browse failed: $errorCode")
                listener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
                listener = null
            }
        }
        listener = l
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure {
                Log.w(TAG, "discoverServices threw", it)
                listener = null
            }
    }

    fun stop() {
        val manager = nsd ?: return
        listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        listener = null
    }

    private fun add(receiver: Receiver) {
        // Replace by host:port rather than name — some receivers announce twice with cosmetic name
        // differences, and two rows for one amp is worse than a slightly stale name.
        _receivers.value = _receivers.value
            .filterNot { it.host == receiver.host && it.port == receiver.port } + receiver
        scope.launch { confirm(receiver) }
    }

    /**
     * Ask the receiver's ZeroConf endpoint who it is.
     *
     * The path is not standardised — most implementations answer on `/` and some on
     * `/zc`, so both are tried. A receiver that answers neither is dropped from the list rather than
     * being offered as something the user can act on.
     */
    private suspend fun confirm(receiver: Receiver) = withContext(Dispatchers.IO) {
        for (path in INFO_PATHS) {
            val url = "http://${receiver.host}:${receiver.port}$path?action=getInfo&version=2.7.1"
            val body = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }.getOrNull() ?: continue
            val json = runCatching { JSONObject(body) }.getOrNull() ?: continue
            update(receiver) {
                it.copy(
                    deviceId = json.optString("deviceID").takeIf { s -> s.isNotBlank() },
                    brand = json.optString("brandDisplayName").takeIf { s -> s.isNotBlank() },
                    model = json.optString("modelDisplayName").takeIf { s -> s.isNotBlank() },
                    activeUser = json.optString("activeUser").takeIf { s -> s.isNotBlank() },
                    confirmed = true,
                )
            }
            Log.i(TAG, "getInfo ok for ${receiver.name} at $url: $body")
            return@withContext
        }
        Log.w(TAG, "no ZeroConf getInfo at ${receiver.host}:${receiver.port}; dropping")
        _receivers.value = _receivers.value
            .filterNot { it.host == receiver.host && it.port == receiver.port }
    }

    private fun update(receiver: Receiver, transform: (Receiver) -> Receiver) {
        _receivers.value = _receivers.value.map {
            if (it.host == receiver.host && it.port == receiver.port) transform(it) else it
        }
    }

    private companion object {
        const val TAG = "ZeroconfDiscovery"
        const val SERVICE_TYPE = "_spotify-connect._tcp."
        val INFO_PATHS = listOf("/", "/zc")
    }
}
