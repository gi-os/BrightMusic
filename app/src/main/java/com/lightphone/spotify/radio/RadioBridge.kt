package com.lightphone.spotify.radio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What is on the radio, and how to control it, for code that cannot reach the ViewModel.
 *
 * [RadioController] is owned by `AppViewModel`, and `PlaybackService` — which owns the
 * lock-screen row — has no route to it. Without this the row only ever saw the Spotify engine's
 * state, so while a station played it showed the last Spotify track and its buttons drove a
 * paused engine.
 *
 * A process-wide object rather than moving RadioController's ownership: the same shape the app
 * already uses for `AppVisibility`, `SleepTimer` and `PodcastSettings`, and it keeps the radio's
 * lifecycle where it is rather than rehoming a player mid-session to serve one screen.
 *
 * The actions are nullable callbacks set by whoever owns the radio, and cleared with it. A caller
 * that arrives before the ViewModel exists gets a no-op rather than a crash.
 */
object RadioBridge {

    /** Everything the lock screen needs to draw and drive a station. */
    data class Snapshot(
        val active: Boolean = false,
        /** The track when one has been identified, otherwise the show or station name. */
        val title: String? = null,
        val isPlaying: Boolean = false,
        /** True once a track has been matched on Spotify — there is nothing to save before that. */
        val canSave: Boolean = false,
        val saved: Boolean = false,
    )

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    fun publish(snapshot: Snapshot) {
        _state.value = snapshot
    }

    /** Set by the ViewModel; both are cleared when it goes. */
    var onPlayPause: (() -> Unit)? = null
    var onToggleSaved: (() -> Unit)? = null

    fun playPause() {
        onPlayPause?.invoke()
    }

    fun toggleSaved() {
        onToggleSaved?.invoke()
    }
}
