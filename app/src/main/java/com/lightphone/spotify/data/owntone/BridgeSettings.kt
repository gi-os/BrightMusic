package com.lightphone.spotify.data.owntone

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Persistent storage for the speaker bridge configuration.
 */
object BridgeSettings {

    private const val PREF_NAME = "bridge_settings"
    private const val KEY_BRIDGE_TYPE = "bridge_type"
    private const val KEY_BRIDGE_URL = "bridge_url"
    private const val KEY_BRIDGE_NAME = "bridge_name"
    private const val KEY_BRIDGE_TOKEN = "bridge_token"

    data class Config(
        val type: String,
        val url: String,
        val name: String,
        val token: String,
    ) {
        val isConfigured: Boolean get() = url.isNotBlank()
    }

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return Config(
            type = prefs.getString(KEY_BRIDGE_TYPE, "") ?: "",
            url = prefs.getString(KEY_BRIDGE_URL, "") ?: "",
            name = prefs.getString(KEY_BRIDGE_NAME, "") ?: "",
            token = prefs.getString(KEY_BRIDGE_TOKEN, "") ?: "",
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BRIDGE_TYPE, config.type)
            .putString(KEY_BRIDGE_URL, config.url)
            .putString(KEY_BRIDGE_NAME, config.name)
            .putString(KEY_BRIDGE_TOKEN, config.token)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * ViewModel-style controller for the OwnTone bridge.
 */
class BridgeController(
    private val config: BridgeSettings.Config,
    private val scope: CoroutineScope,
) {
    data class UiState(
        val speakers: List<OwntoneOutput> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val playerState: OwntonePlayerState? = null,
        /** Whether the server answered the last probe. Null until the first one returns. */
        val reachable: Boolean? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val api = OwntoneApi(config.url.trimEnd('/'))

    val isConfigured: Boolean get() = config.isConfigured

    /**
     * Ask the server whether it is actually there.
     *
     * "Configured" and "connected" are different claims, and the settings screen was making the
     * second from the first: a saved URL read as "Connected" on the far side of the city. This
     * is what turns that label honest.
     */
    fun checkReachable() {
        if (!isConfigured) return
        scope.launch {
            val ok = api.getPlayerState().isSuccess
            _state.value = _state.value.copy(reachable = ok)
        }
    }

    /**
     * Turn every AirPlay output off, and park the player.
     *
     * Called once per app start. Speaker selection lives on the OwnTone server, so it survives
     * anything the phone does — which meant a fresh launch could route straight to whichever
     * room was left on last night, silently. A session now begins with AirPlay off and the
     * phone's own speaker as the default; the Devices screen turns rooms on deliberately.
     *
     * The player is stopped as well as deselected: without that, OwnTone keeps "playing" to
     * nothing, and the first speaker toggled back on would resume old audio unannounced.
     * Non-AirPlay outputs (the server's own soundcard, say) are not this app's to touch.
     */
    fun silenceAirplay() {
        if (!isConfigured) return
        scope.launch {
            api.stopPlayer()
            api.listOutputs().onSuccess { outputs ->
                outputs
                    .filter { it.selected && it.type.startsWith("AirPlay") }
                    .forEach { api.setOutputSelected(it.id, false) }
                // Re-read rather than guess, so the Devices screen opens on the truth.
                refreshSpeakers()
            }
        }
    }

    fun refreshSpeakers() {
        if (!isConfigured) return
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            api.listOutputs()
                .onSuccess { outputs ->
                    android.util.Log.d("BridgeCtrl", "listOutputs: ${outputs.size} speakers")
                    _state.value = _state.value.copy(speakers = outputs, loading = false, reachable = true)
                }
                .onFailure { e ->
                    android.util.Log.e("BridgeCtrl", "listOutputs failed: ${e.message}", e)
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "${e.message} (url: ${config.url})",
                        reachable = false,
                    )
                }
        }
    }

    fun toggleSpeaker(outputId: String, enabled: Boolean) {
        if (!isConfigured) return
        scope.launch {
            _state.value = _state.value.copy(loading = true)
            api.setOutputSelected(outputId, enabled)
                .onSuccess { refreshSpeakers() }
                .onFailure { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to toggle speaker")
                }
        }
    }

    fun refreshPlayerState() {
        if (!isConfigured) return
        scope.launch {
            api.getPlayerState()
                .onSuccess { ps -> _state.value = _state.value.copy(playerState = ps) }
        }
    }

    fun setVolume(volume: Int, outputId: String? = null) {
        if (!isConfigured) return
        scope.launch { api.setVolume(volume, outputId) }
    }

    fun adjustVolume(outputId: String, delta: Int) {
        if (!isConfigured) return
        scope.launch {
            // Get current volume for this output, then set new value
            api.listOutputs().onSuccess { outputs ->
                val current = outputs.find { it.id == outputId }?.volume ?: 50
                val newVol = (current + delta).coerceIn(0, 100)
                api.setVolume(newVol, outputId)
            }
        }
    }

    /**
     * Route a radio stream URL to OwnTone — clears queue and starts playback immediately.
     *
     * Suspends and reports whether it worked, because the caller's next move depends on it:
     * a bridge that cannot be reached (not home, server down) must fall back to playing the
     * stream on the phone rather than pretending the HomePods are on.
     */
    suspend fun playRadioStream(url: String): Boolean {
        if (!isConfigured) return false
        _state.value = _state.value.copy(loading = true)

        // A queue add can succeed with every speaker toggled off — OwnTone "plays" to nothing,
        // the phone shows a station running, and the room is silent. That is not routing, so
        // it is treated the same as an unreachable server: the caller falls back to the phone.
        val outputs = api.listOutputs().getOrNull()
        if (outputs == null) {
            _state.value = _state.value.copy(loading = false, reachable = false, error = "Bridge not reachable")
            return false
        }
        _state.value = _state.value.copy(speakers = outputs, reachable = true)
        if (outputs.none { it.selected }) {
            _state.value = _state.value.copy(loading = false, error = "No bridge speaker selected")
            return false
        }

        // Pause first. With pipe_autostart configured, OwnTone follows whichever source is
        // live — pausing parks it so the queue clear+add below decides what plays, rather
        // than whatever pipe happens to still be written to.
        api.stopPlayer()
        if (api.playUrl(url).isFailure) {
            _state.value = _state.value.copy(loading = false, error = "Failed to queue the stream")
            return false
        }

        // Trust, but verify — and only claim success on evidence. `playback=start` has been
        // observed to land with the player still paused, and an accepted queue add says nothing
        // about the stream actually opening. Nudge and re-check briefly; if it never reaches
        // "play", report failure so the caller can put the audio somewhere that works.
        repeat(4) {
            val ps = api.getPlayerState().getOrNull()
            if (ps?.state == "play") {
                _state.value = _state.value.copy(loading = false, error = null)
                return true
            }
            api.resumePlayer()
            delay(400)
        }
        _state.value = _state.value.copy(
            loading = false,
            error = "Bridge accepted the stream but never started playing",
        )
        return false
    }

    fun stopPlayer() {
        if (!isConfigured) return
        scope.launch {
            api.stopPlayer()
        }
    }

    fun resumePlayer() {
        if (!isConfigured) return
        scope.launch {
            api.resumePlayer()
        }
    }
}
