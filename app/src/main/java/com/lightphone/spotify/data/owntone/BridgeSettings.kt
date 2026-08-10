package com.lightphone.spotify.data.owntone

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistent storage for the speaker bridge configuration.
 * Keys match the QR code payload and are stored in app-level SharedPreferences.
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
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

/**
 * ViewModel-style controller for the OwnTone bridge.
 * Holds live state for the speaker picker and exposes actions.
 */
class BridgeController(
    private val config: BridgeSettings.Config,
) {
    data class UiState(
        val speakers: List<OwntoneOutput> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val playerState: OwntonePlayerState? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val api = OwntoneApi(config.url)

    val isConfigured: Boolean get() = config.isConfigured

    fun refreshSpeakers() {
        if (!isConfigured) return
        _state.value = _state.value.copy(loading = true, error = null)
        api.listOutputs()
            .onSuccess { outputs ->
                _state.value = _state.value.copy(
                    speakers = outputs,
                    loading = false,
                    error = null,
                )
            }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Failed to reach bridge",
                )
            }
    }

    fun toggleSpeaker(outputId: String, enabled: Boolean) {
        if (!isConfigured) return
        _state.value = _state.value.copy(loading = true)
        api.setOutputSelected(outputId, enabled)
            .onSuccess { refreshSpeakers() }
            .onFailure { e ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: "Failed to toggle speaker",
                )
            }
    }

    fun refreshPlayerState() {
        if (!isConfigured) return
        api.getPlayerState()
            .onSuccess { ps -> _state.value = _state.value.copy(playerState = ps) }
            .onFailure { /* silently ignore — polling is best-effort */ }
    }

    fun setVolume(volume: Int, outputId: String? = null) {
        if (!isConfigured) return
        api.setVolume(volume, outputId)
    }
}
