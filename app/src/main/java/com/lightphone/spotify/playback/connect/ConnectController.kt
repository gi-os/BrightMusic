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
    /** Device id LightPhono is currently driving, or null when playing locally. */
    val activeRemoteId: String? = null,
    val activeRemoteName: String? = null,
    val transferring: Boolean = false,
    val error: String? = null,
    /** Set when the stored token lacks the player scopes and Step 2 must be redone. */
    val needsReauthorize: Boolean = false,
) {
    val isRemote: Boolean get() = activeRemoteId != null
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
) {

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    private val _remotePlayback = MutableStateFlow<RemotePlayback?>(null)

    /** Remote player state, or null while playing locally. */
    val remotePlayback: StateFlow<RemotePlayback?> = _remotePlayback.asStateFlow()

    private var pollJob: Job? = null

    /** Reload the device list. Safe to call on every screen entry. */
    fun refreshDevices() {
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val devices = webApi.devices()
                val active = devices.firstOrNull { it.isActive }
                _state.value = _state.value.copy(
                    devices = devices,
                    loading = false,
                    // Trust Spotify over our own memory: if the user moved playback from
                    // another client, the active device changed without us doing anything.
                    activeRemoteId = active?.id,
                    activeRemoteName = active?.name,
                    needsReauthorize = false,
                )
                // Adopting a session found here MUST also start the poll. Setting
                // activeRemoteId alone would flip the app into remote mode with a null
                // remotePlayback, so the transport would drive the far device while the
                // screen kept showing local state.
                if (active?.id != null) startPolling() else stopAdoptedPolling()
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
        scope.launch {
            _state.value = _state.value.copy(transferring = true, error = null)
            try {
                if (localUris.isNotEmpty()) {
                    webApi.remotePlayUris(
                        uris = localUris,
                        offsetIndex = localIndex,
                        positionMs = localPositionMs,
                        deviceId = deviceId,
                    )
                } else {
                    webApi.transferPlayback(deviceId, play = true)
                }
                onPauseLocal()
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
                refreshDevices()
            } catch (e: Exception) {
                Log.w(TAG, "transfer to ${device.name} failed", e)
                _state.value = _state.value.copy(transferring = false, error = e.message)
            }
        }
    }

    /** Give control back to this phone. Playback is left paused on the remote device. */
    fun returnToLocal() {
        stopPolling()
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

    fun pause() = command { webApi.remotePause(it) }.also { optimistic { it.copy(isPlaying = false) } }

    fun next() = command { webApi.remoteNext(it) }

    fun previous() = command { webApi.remotePrevious(it) }

    fun seek(positionMs: Long) = command { webApi.remoteSeek(positionMs, it) }
        .also { optimistic { state -> state.copy(positionMs = positionMs) } }

    fun setShuffle(enabled: Boolean) = command { webApi.remoteShuffle(enabled, it) }
        .also { optimistic { it.copy(shuffleEnabled = enabled) } }

    fun setRepeat(mode: RepeatMode) = command { webApi.remoteRepeat(mode.toSpotifyState(), it) }
        .also { optimistic { it.copy(repeatMode = mode) } }

    fun setVolume(percent: Int) = command { webApi.remoteVolume(percent, it) }

    private fun command(block: suspend (String?) -> Unit) {
        val deviceId = _state.value.activeRemoteId ?: return
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
                        if (deviceId != null && deviceId != _state.value.activeRemoteId) {
                            // Someone moved playback elsewhere. Follow it rather than
                            // fighting over the session.
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
