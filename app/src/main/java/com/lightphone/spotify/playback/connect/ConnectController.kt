package com.lightphone.spotify.playback.connect

import android.util.Log
import com.lightphone.spotify.data.webapi.ConnectNoActiveDeviceException
import com.lightphone.spotify.data.webapi.ConnectScopeException
import com.lightphone.spotify.data.webapi.SpotifyDevice
import com.lightphone.spotify.data.webapi.SpotifyPlayerState
import com.lightphone.spotify.data.webapi.SpotifyWebApi
import com.lightphone.spotify.ffi.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the device picker renders. */
data class ConnectUiState(
    val devices: List<SpotifyDevice> = emptyList(),
    val loading: Boolean = false,
    /**
     * Device this app handed playback to, or null when playing locally. Set only by an explicit
     * [ConnectController.transferTo] — never inferred from Spotify reporting a device as active,
     * which would hijack the transport (see `refreshDevices`).
     */
    val activeRemoteId: String? = null,
    val activeRemoteName: String? = null,
    /**
     * A device Spotify reports as active that we do not own — typically the user's desktop. Labelled
     * in the picker so the screen is honest about it, but the transport stays local.
     */
    val externalActiveId: String? = null,
    val transferring: Boolean = false,
    val error: String? = null,
    /** Set when the stored token lacks the player scopes and Step 2 must be redone. */
    val needsReauthorize: Boolean = false,
) {
    val isRemote: Boolean get() = activeRemoteId != null

    /** The device being driven, when it is still in the list. */
    val activeRemoteDevice: SpotifyDevice? get() = devices.firstOrNull { it.id == activeRemoteId }
}

/**
 * Spotify Connect **controller** — LightPhono driving playback on someone else's
 * hardware (a speaker, a desktop, a TV).
 *
 * ### This phone is not a Connect device
 * `rust/spotify-core` depends on librespot with `default-features = false` and never
 * pulls `librespot-connect`, so no Spirc loop runs and Spotify has no idea this phone
 * exists as a target. Two consequences shape everything below:
 *
 *  1. `GET /me/player/devices` only ever returns *other* devices, so nothing has to be
 *     filtered out of the picker.
 *  2. Playback cannot be transferred *back* to the phone through the Web API. Returning
 *     to local means resuming the local librespot engine directly, which is what
 *     [returnToLocal] does.
 *
 * Making the phone a real Connect target would mean adding the connect crate and having
 * both Spirc and the existing engine believe they own the audio sink.
 *
 * ### Why polling
 * Spotify exposes no push API for player state outside the undocumented dealer
 * websocket. So while a remote device is active this polls `GET /me/player`. The
 * interval is deliberately slack — [POLL_INTERVAL_MS] — because a remote session's
 * progress bar does not need to be frame-accurate and the LPIII has a small battery.
 * Polling stops the moment playback returns to local.
 *
 * ### Ownership
 * Handing off pauses the local engine rather than tearing it down, so the session stays
 * warm and coming back to local playback does not re-handshake.
 */
class ConnectController(
    private val webApi: SpotifyWebApi,
    private val scope: CoroutineScope,
    /** Pauses the local engine when handing off. */
    private val onPauseLocal: () -> Unit,
    /** Resumes the local engine when a handoff fails after it was already paused. */
    private val onResumeLocal: () -> Unit,
) {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private val _remotePlayback = MutableStateFlow<RemotePlayback?>(null)

    /** Remote player state, or null while playing locally. */
    val remotePlayback: StateFlow<RemotePlayback?> = _remotePlayback.asStateFlow()

    private var pollJob: Job? = null

    /**
     * Consecutive `/me/player/devices` responses that did not contain the device we are driving.
     *
     * `refreshDevices` used to drop remote mode the first time the owned device was absent from the
     * list, and it is called on every entry to the picker. But Spotify's device list is not a reliable
     * liveness signal: a speaker drops out while it re-registers, a ZeroConf-claimed receiver can come
     * back under a *different* device id, and the list is eventually-consistent with the account. The
     * result was the app silently deciding it was local while the speaker played on — which is both
     * "playing on multiple devices" and "can't change the player's settings", since every remote
     * command in [command] returns early on a null [ConnectUiState.activeRemoteId].
     *
     * So absence now has to be corroborated: [MAX_DEVICE_LIST_MISSES] consecutive misses *and* no
     * remote playback state, before ownership is released.
     */
    private var deviceListMisses = 0

    /** Reload the device list. Safe to call on every screen entry. */
    fun refreshDevices() {
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val devices = webApi.devices()
                val active = devices.firstOrNull { it.isActive }
                val owned = _state.value.activeRemoteId

                // Only a transfer WE performed puts this app in remote mode.
                //
                // This used to adopt whatever device Spotify reported as active, which quietly
                // hijacked the transport: with Spotify open on a desktop, merely opening "Play on"
                // made LightPhono believe it was remote, and play/pause then drove the desktop while
                // the phone's own playback ignored the button. Spotify reports an active device for
                // the whole account, not for this client, so "active" says nothing about who should
                // be driving it.
                //
                // While we do own a session, the far end is still authoritative about where it went
                // (see the poll loop), so an owned id is refreshed here rather than pinned.
                val listed = owned != null && devices.any { it.id == owned }
                if (listed) deviceListMisses = 0 else if (owned != null) deviceListMisses++

                // Keep driving a session that is merely missing from this one response. Playback state
                // is the authoritative signal, so a device we cannot see but that is still reporting
                // playback stays ours.
                val stillOurs = owned != null && (
                    listed ||
                        _remotePlayback.value != null ||
                        deviceListMisses < MAX_DEVICE_LIST_MISSES
                    )
                _state.value = _state.value.copy(
                    devices = devices,
                    loading = false,
                    activeRemoteId = if (stillOurs) owned else null,
                    activeRemoteName = if (stillOurs) {
                        devices.firstOrNull { it.id == owned }?.name ?: _state.value.activeRemoteName
                    } else {
                        null
                    },
                    // Shown as "Playing here" in the list so the screen still tells the truth about
                    // where the account is playing, without claiming it.
                    externalActiveId = active?.id?.takeIf { it != owned },
                    needsReauthorize = false,
                )
                if (stillOurs) startPolling() else stopAdoptedPolling()
            } catch (e: ConnectScopeException) {
                _state.value = _state.value.copy(
                    loading = false,
                    needsReauthorize = true,
                    error = e.message,
                )
            } catch (e: Exception) {
                Log.w(TAG, "device list failed", e)
                _state.value = _state.value.copy(loading = false, error = e.message)
            }
        }
    }

    /**
     * Wait for [deviceId] to turn up in the account's device list.
     *
     * A receiver claimed over ZeroConf does not appear in `/me/player/devices` the instant it accepts
     * the login: it has to open its own session with Spotify first, which takes a few seconds on a
     * speaker. Polling here rather than making the caller guess a delay means the transfer happens as
     * soon as it is possible and not before — a transfer to a device Spotify has not registered yet
     * fails with 404.
     *
     * The list is written into state as it is fetched, so the picker fills in while this waits.
     */
    suspend fun awaitDevice(deviceId: String, timeoutMs: Long = REGISTER_TIMEOUT_MS): SpotifyDevice? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val devices = runCatching { webApi.devices() }.getOrNull()
            if (devices != null) {
                _state.value = _state.value.copy(devices = devices, loading = false)
                devices.firstOrNull { it.id == deviceId }?.let { return it }
            }
            if (System.currentTimeMillis() + REGISTER_POLL_MS >= deadline) return null
            delay(REGISTER_POLL_MS)
        }
    }

    /**
     * Move playback to [device].
     *
     * [localUris] is the queue LightPhono currently holds. When the local engine has
     * something loaded we re-send it to the target rather than calling transfer alone,
     * because a plain transfer moves whatever *Spotify* thinks is playing, which after
     * local librespot playback is usually nothing.
     */
    fun transferTo(
        device: SpotifyDevice,
        localUris: List<String> = emptyList(),
        localIndex: Int = 0,
        localPositionMs: Long = 0,
    ) {
        val deviceId = device.id ?: return
        if (deviceId == _state.value.activeRemoteId) return
        val previousRemoteId = _state.value.activeRemoteId
        scope.launch {
            _state.value = _state.value.copy(transferring = true, error = null)
            try {
                // Silence this end *before* the far end starts.
                //
                // This used to run after the remote call returned, which left a window — one network
                // round trip, longer on a slow connection — where the speaker had started and the
                // phone had not stopped. Worse, a remote call that the server accepted but whose
                // response never arrived threw, so the pause never ran at all and both played
                // indefinitely. That was the "playing on multiple devices" bug.
                //
                // Pausing first means a failed transfer leaves the user paused rather than doubled,
                // which the catch blocks below undo.
                if (previousRemoteId == null) {
                    onPauseLocal()
                } else {
                    // Hopping speaker to speaker. The outgoing device is paused explicitly rather than
                    // trusted to stop on its own: Spotify moves a session it fully controls, but a
                    // ZeroConf-claimed receiver frequently keeps playing, which is the same doubling
                    // by a different route.
                    runCatching { webApi.remotePause(previousRemoteId) }
                }

                // A live remote session is moved with Spotify's own transfer, which carries the queue
                // and position across. Re-sending `localUris` here would replace the remote queue with
                // whatever the *paused local engine* still holds — usually stale, sometimes empty.
                if (previousRemoteId == null && localUris.isNotEmpty()) {
                    webApi.remotePlayUris(
                        uris = localUris,
                        offsetIndex = localIndex,
                        positionMs = localPositionMs,
                        deviceId = deviceId,
                    )
                } else {
                    webApi.transferPlayback(deviceId, play = true)
                }
                deviceListMisses = 0
                _state.value = _state.value.copy(
                    transferring = false,
                    activeRemoteId = deviceId,
                    activeRemoteName = device.name,
                )
                startPolling()
            } catch (e: ConnectScopeException) {
                _state.value = _state.value.copy(
                    transferring = false,
                    needsReauthorize = true,
                    error = e.message,
                )
            } catch (e: ConnectNoActiveDeviceException) {
                // The speaker idled out between listing and tapping. Re-list rather than
                // blaming the user.
                _state.value = _state.value.copy(transferring = false, error = e.message)
                onTransferFailed(previousRemoteId)
                refreshDevices()
            } catch (e: Exception) {
                Log.w(TAG, "transfer to ${device.name} failed", e)
                _state.value = _state.value.copy(transferring = false, error = e.message)
                onTransferFailed(previousRemoteId)
            }
        }
    }

    /**
     * Put audio back where it was after a failed handoff.
     *
     * Pausing before transferring (see [transferTo]) means a failure leaves everything silent, so the
     * pause has to be undone. Coming from local, that is resuming the local engine; coming from another
     * remote device, it is un-pausing that device — the session never left it.
     */
    private fun onTransferFailed(previousRemoteId: String?) {
        if (previousRemoteId == null) {
            onResumeLocal()
        } else {
            scope.launch { runCatching { webApi.remotePlay(previousRemoteId) } }
        }
    }

    /** Give control back to this phone. Playback is left paused on the remote device. */
    fun returnToLocal() {
        stopPolling()
        deviceListMisses = 0
        val deviceId = _state.value.activeRemoteId
        _state.value = _state.value.copy(activeRemoteId = null, activeRemoteName = null)
        _remotePlayback.value = null
        if (deviceId != null) {
            scope.launch { runCatching { webApi.remotePause(deviceId) } }
        }
    }

    // --- remote transport ---------------------------------------------------
    // Each command optimistically updates the local mirror so the UI responds on tap
    // instead of waiting for the next poll, then lets the poll correct it.

    fun play() = command { webApi.remotePlay(it) }.also { optimistic { it.copy(isPlaying = true) } }

    /**
     * Start a new queue on the remote device — the missing half of "every command goes over
     * the Web API while remote". Transport buttons were routed by destination from day one,
     * but *choosing a song* went through `playTracks`, which only knew the local engine: with
     * a speaker active, tapping a track either started the phone playing over the speaker or
     * looked like a dead tap. Now the same tap re-queues the remote.
     *
     * The mirror is replaced, not merged: a queue start defines what is playing, and waiting
     * for the next poll would leave the old track on screen for seconds after the speaker has
     * already moved on. Poll corrects the details (duration, art) shortly after.
     */
    fun playUris(
        uris: List<String>,
        startIndex: Int,
        positionMs: Long = 0L,
        startUri: String? = null,
        startTitle: String? = null,
        startArtist: String? = null,
        startArtUrl: String? = null,
        startAlbumId: String? = null,
        startDurationMs: Long = 0L,
    ) {
        command { webApi.remotePlayUris(uris, startIndex, positionMs, it) }
        val prev = _remotePlayback.value
        _remotePlayback.value = RemotePlayback(
            uri = startUri,
            title = startTitle,
            artist = startArtist,
            artUrl = startArtUrl,
            albumId = startAlbumId,
            isPlaying = true,
            positionMs = positionMs,
            durationMs = startDurationMs,
            shuffleEnabled = prev?.shuffleEnabled ?: false,
            repeatMode = prev?.repeatMode ?: RepeatMode.OFF,
            deviceName = prev?.deviceName ?: _state.value.activeRemoteName,
            volumePercent = prev?.volumePercent,
        )
    }

    fun pause() = command { webApi.remotePause(it) }.also { optimistic { it.copy(isPlaying = false) } }

    fun next() = command { webApi.remoteNext(it) }

    fun previous() = command { webApi.remotePrevious(it) }

    fun seek(positionMs: Long) = command { webApi.remoteSeek(positionMs, it) }
        .also { optimistic { state -> state.copy(positionMs = positionMs) } }

    fun setShuffle(enabled: Boolean) = command { webApi.remoteShuffle(enabled, it) }
        .also { optimistic { it.copy(shuffleEnabled = enabled) } }

    fun setRepeat(mode: RepeatMode) = command { webApi.remoteRepeat(mode.toSpotifyState(), it) }
        .also { optimistic { it.copy(repeatMode = mode) } }

    /**
     * Set the active remote device's volume, 0-100.
     *
     * Mirrored optimistically like the other commands, because the volume the user is nudging is read
     * back out of [RemotePlayback] — without it the number would jump back to the old value until the
     * next poll and the control would feel broken.
     */
    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        command { webApi.remoteVolume(clamped, it) }
        optimistic { it.copy(volumePercent = clamped) }
    }

    private fun command(block: suspend (String?) -> Unit) {
        val deviceId = _state.value.activeRemoteId
        if (deviceId == null) {
            // Previously a silent `return`, which is how a lost session became "the buttons do
            // nothing" with no explanation. See [deviceListMisses].
            Log.w(TAG, "remote command with no active device")
            return
        }
        scope.launch {
            try {
                block(deviceId)
            } catch (e: ConnectNoActiveDeviceException) {
                // Target vanished mid-session — fall back to local so the user is not
                // left tapping a dead transport.
                Log.i(TAG, "remote device gone, returning to local")
                _state.value = _state.value.copy(
                    activeRemoteId = null,
                    activeRemoteName = null,
                    error = e.message,
                )
                stopPolling()
                _remotePlayback.value = null
            } catch (e: Exception) {
                Log.w(TAG, "remote command failed", e)
                _state.value = _state.value.copy(error = e.message)
            }
        }
    }

    private fun optimistic(update: (RemotePlayback) -> RemotePlayback) {
        _remotePlayback.value?.let { _remotePlayback.value = update(it) }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // --- polling ------------------------------------------------------------

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val remote = webApi.playerState()
                    if (remote == null) {
                        // Nothing playing anywhere: the session ended on the far side.
                        _remotePlayback.value = null
                    } else {
                        val device = remote.device
                        val deviceId = device?.id
                        // Follow a session we already own when it moves; do not adopt one we never
                        // had. The poll only runs while owned, but a move can land on a device we
                        // did not pick, and continuing to drive the session is right there.
                        if (deviceId != null && deviceId != _state.value.activeRemoteId) {
                            _state.value = _state.value.copy(
                                activeRemoteId = deviceId,
                                activeRemoteName = device.name,
                            )
                        }
                        _remotePlayback.value = remote.toRemotePlayback()
                    }
                } catch (e: ConnectNoActiveDeviceException) {
                    _remotePlayback.value = null
                } catch (e: Exception) {
                    Log.w(TAG, "player state poll failed", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Wind down a session we only adopted by observation (not by an explicit transfer)
     * once Spotify reports no active device. Keeps the app from sitting in remote mode
     * with nothing on the other end.
     */
    private fun stopAdoptedPolling() {
        stopPolling()
        _remotePlayback.value = null
    }

    companion object {
        private const val TAG = "ConnectController"

        /**
         * 5s. Fast enough that a skip on the speaker shows up before the user wonders,
         * slow enough not to matter for battery or the 429 budget (the Web API allows
         * far more, but this runs for the whole time a remote session is open).
         */
        private const val POLL_INTERVAL_MS = 5_000L

        /** How long a just-claimed receiver gets to register itself with Spotify. */
        private const val REGISTER_TIMEOUT_MS = 20_000L
        private const val REGISTER_POLL_MS = 1_500L

        /**
         * How many consecutive device lists may omit the device we are driving before ownership is
         * released. Three at the picker's refresh rate is long enough to ride out a re-registering
         * speaker and short enough that a genuinely dead session is not clung to.
         */
        private const val MAX_DEVICE_LIST_MISSES = 3
    }
}

/** Remote player state in the same shape the local UI already understands. */
data class RemotePlayback(
    val uri: String?,
    val title: String?,
    val artist: String?,
    val artUrl: String?,
    val albumId: String?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    val deviceName: String?,
    val volumePercent: Int?,
)

private fun SpotifyPlayerState.toRemotePlayback(): RemotePlayback = RemotePlayback(
    uri = item?.uri,
    title = item?.name,
    artist = item?.artists?.joinToString { it.name }?.takeIf { it.isNotBlank() },
    // Spotify orders images widest-first; the smallest is plenty for a 200dp cover on a
    // greyscale panel and saves the download.
    artUrl = item?.album?.images?.minByOrNull { it.width ?: Int.MAX_VALUE }?.url,
    albumId = item?.album?.id?.takeIf { it.isNotBlank() },
    isPlaying = isPlaying,
    positionMs = progressMs ?: 0L,
    durationMs = item?.durationMs ?: 0L,
    shuffleEnabled = shuffleState,
    repeatMode = repeatState.toRepeatMode(),
    deviceName = device?.name,
    volumePercent = device?.volumePercent,
)

private fun String?.toRepeatMode(): RepeatMode = when (this) {
    "track" -> RepeatMode.TRACK
    "context" -> RepeatMode.CONTEXT
    else -> RepeatMode.OFF
}

private fun RepeatMode.toSpotifyState(): String = when (this) {
    RepeatMode.TRACK -> "track"
    RepeatMode.CONTEXT -> "context"
    else -> "off"
}
