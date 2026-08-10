package com.lightphone.spotify.data.owntone

import android.content.Context
import kotlinx.coroutines.CoroutineScope
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
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val api = OwntoneApi(config.url.trimEnd('/'))

    val isConfigured: Boolean get() = config.isConfigured

    fun refreshSpeakers() {
        if (!isConfigured) return
        scope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            api.listOutputs()
                .onSuccess { outputs ->
                    android.util.Log.d("BridgeCtrl", "listOutputs: ${outputs.size} speakers")
                    _state.value = _state.value.copy(speakers = outputs, loading = false)
                }
                .onFailure { e ->
                    android.util.Log.e("BridgeCtrl", "listOutputs failed: ${e.message}", e)
                    _state.value = _state.value.copy(loading = false, error = "${e.message} (url: ${config.url})")
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

    /** Route a radio stream URL to OwnTone — clears queue and starts playback immediately. */
    fun playRadioStream(url: String) {
        if (!isConfigured) return
        scope.launch {
            _state.value = _state.value.copy(loading = true)
            api.playUrl(url)
                .onSuccess { _state.value = _state.value.copy(loading = false) }
                .onFailure { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message ?: "Failed to play stream")
                }
        }
    }
}
