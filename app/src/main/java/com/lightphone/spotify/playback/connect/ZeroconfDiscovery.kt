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
 * ### What this does
 * The discovery half: browse for the service, resolve each one, and read its ZeroConf `getInfo`.
 * Claiming a receiver — `action=addUser`, which is what makes it visible to the Web API and therefore
 * playable — is [ZeroconfClaim]. Discovery keeps hold of the `publicKey` and `tokenType` from
 * `getInfo` only so the UI can tell a claimable receiver from a stray HTTP server; the claim re-reads
 * `getInfo` for itself, because a receiver that restarted has a new key.
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
        /**
         * Path to the receiver's ZeroConf implementation, from the mDNS TXT record's `CPath`.
         *
         * This is where the endpoint actually lives and it is **not** guessable: the spec lets a
         * device put it anywhere, and real hardware uses `/`, `/zc`, `/zc/0` and `/zeroconf` among
         * others. Null when the TXT record carried none, in which case the known paths get tried.
         */
        val cpath: String? = null,
        /** False once getInfo has been tried on every candidate path and none answered. */
        val reachable: Boolean = true,
        /** Set when getInfo says a user is already logged in — usually then visible to the Web API. */
        val activeUser: String? = null,
        /** Receiver's DH public key, base64. Without one it cannot be claimed. */
        val publicKey: String? = null,
        /** `default` or `accesstoken`; which payload [ZeroconfClaim] leads with. */
        val tokenType: String = ZeroconfClaim.TOKEN_TYPE_DEFAULT,
    ) {
        /** A receiver we could try to log the account into. */
        val claimable: Boolean get() = confirmed && deviceId != null && publicKey != null

        /** What to call it on screen, and in anything it says back. */
        fun label(): String = listOfNotNull(brand, model).joinToString(" ").ifBlank { name }
    }

    private val _receivers = MutableStateFlow<List<Receiver>>(emptyList())
    val receivers: StateFlow<List<Receiver>> = _receivers.asStateFlow()

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.DiscoveryListener? = null

    private val resolveLock = Any()
    private val pendingResolves = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

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
                enqueueResolve(svc)
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

    /**
     * Resolve one service at a time.
     *
     * `resolveService` cannot be run concurrently. Firing one per `onServiceFound` — which is what
     * this did — makes Android **cross the results**: on a network with five receivers the phone
     * listed one row carrying one device's name and another device's address, and everything else
     * silently failed to resolve. That is not a race we can win by retrying, so resolves are queued
     * and the next one starts only when the last has come back either way.
     */
    private fun enqueueResolve(service: NsdServiceInfo) {
        synchronized(resolveLock) {
            pendingResolves.addLast(service)
            if (resolving) return
        }
        resolveNext()
    }

    private fun resolveNext() {
        val manager = nsd ?: return
        val next = synchronized(resolveLock) {
            val head = pendingResolves.removeFirstOrNull()
            resolving = head != null
            head
        } ?: return

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                Log.w(TAG, "resolve failed for ${next.serviceName}: $errorCode")
                finishResolve()
            }

            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                try {
                    val r = resolved ?: return
                    @Suppress("DEPRECATION")
                    val address = r.host ?: return
                    // A link-local v6 address is only usable with its scope id, which a URL cannot
                    // carry. mDNS advertises the same receiver over IPv4 as well, so dropping this
                    // record loses nothing and saves a row that could never be reached.
                    if (address is java.net.Inet6Address && address.isLinkLocalAddress) {
                        Log.i(TAG, "skipping link-local v6 for ${r.serviceName}")
                        return
                    }
                    val host = address.hostAddress ?: return
                    // Logged in full because a receiver that answers on an unexpected path is
                    // otherwise indistinguishable from one that is not there.
                    val attrs = runCatching { r.attributes }.getOrNull().orEmpty()
                    Log.i(
                        TAG,
                        "resolved ${r.serviceName} at $host:${r.port} txt=" +
                            attrs.entries.joinToString { (k, v) -> "$k=${v?.let { b -> String(b) }}" },
                    )
                    add(
                        Receiver(
                            name = r.serviceName ?: host,
                            host = host,
                            port = r.port,
                            cpath = attrs["CPath"]?.let { String(it) }?.takeIf(String::isNotBlank),
                        ),
                    )
                } finally {
                    finishResolve()
                }
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            manager.resolveService(next, listener)
        }.onFailure {
            Log.w(TAG, "resolveService threw for ${next.serviceName}", it)
            finishResolve()
        }
    }

    private fun finishResolve() {
        synchronized(resolveLock) { resolving = false }
        resolveNext()
    }

    fun stop() {
        val manager = nsd ?: return
        listener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        listener = null
        // A queue left full would resolve services for a screen that is gone, and the results would
        // land in a flow nobody is collecting.
        synchronized(resolveLock) { pendingResolves.clear() }
    }

    private fun add(receiver: Receiver) {
        // Replace by host:port rather than name — some receivers announce twice with cosmetic name
        // differences, and two rows for one amp is worse than a slightly stale name.
        _receivers.value = _receivers.value
            .filterNot { it.host == receiver.host && it.port == receiver.port } + receiver
        scope.launch { confirm(receiver) }
    }

    /**
     * Ask the receiver's ZeroConf endpoint who it is, via the same call the claim uses.
     *
     * A receiver that answers nowhere used to be dropped from the list. That turned the commonest
     * failure — the endpoint being on a path we did not try — into an empty screen with nothing to
     * explain it, which is exactly the case worth showing. It stays listed as unreachable instead.
     */
    private suspend fun confirm(receiver: Receiver) = withContext(Dispatchers.IO) {
        val info = ZeroconfClaim.fetchInfo(http, receiver.host, receiver.port, receiver.cpath)
        if (info == null) {
            Log.w(TAG, "no ZeroConf getInfo at ${receiver.host}:${receiver.port} (CPath=${receiver.cpath})")
            update(receiver) { it.copy(reachable = false) }
            return@withContext
        }
        update(receiver) {
            it.copy(
                deviceId = info.deviceId,
                brand = info.brand,
                model = info.model,
                activeUser = info.activeUser,
                publicKey = info.publicKey,
                tokenType = info.tokenType,
                cpath = info.path,
                reachable = true,
                confirmed = true,
            )
        }
    }

    private fun update(receiver: Receiver, transform: (Receiver) -> Receiver) {
        _receivers.value = _receivers.value.map {
            if (it.host == receiver.host && it.port == receiver.port) transform(it) else it
        }
    }

    private companion object {
        const val TAG = "ZeroconfDiscovery"
        const val SERVICE_TYPE = "_spotify-connect._tcp."
    }
}
