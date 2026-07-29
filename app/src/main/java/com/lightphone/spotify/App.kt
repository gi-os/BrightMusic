package com.lightphone.spotify

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.lightphone.spotify.data.backend.BackendPreferences
import com.lightphone.spotify.playback.PlaybackController
import com.lightphone.spotify.playback.download.OfflinePinHygiene
import com.lightphone.spotify.ui.light.ArtworkPreferences
import com.lightphone.spotify.ui.light.ArtworkSettings
import com.lightphone.spotify.ui.light.ThemePreferences

class App : Application() {
    /**
     * Null until [ensureController] runs. Spotify is the only backend, so the choice is
     * pinned on first launch rather than asked about.
     */
    var controller: PlaybackController? = null
        private set

    override fun onCreate() {
        super.onCreate()
        ThemePreferences(this).applyToController()
        // Seed the observable artwork state before any cover can be composed, so the
        // first frame does not load a colour image and then re-fetch a dithered one.
        ArtworkSettings.load(ArtworkPreferences(this))
        // Upstream phono gated this on a first-run Spotify/TIDAL picker. LightPhono has
        // one backend, so pin the choice and build the controller straight away.
        BackendPreferences(this).ensureSpotify()
        ensureController()
    }

    /** Build the controller for the persisted backend choice (idempotent). */
    fun ensureController(): PlaybackController {
        controller?.let { return it }
        OfflinePinHygiene.enforce(this)
        val c = PlaybackController.get(this)
        controller = c
        if (!foregroundObserverRegistered) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(AppForegroundObserver(this))
            foregroundObserverRegistered = true
        }
        return c
    }

    /** Drop the controller after logout so the service picker can rebuild for a new choice. */
    fun clearController() {
        controller = null
        PlaybackController.clearInstance()
    }

    companion object {
        @Volatile
        private var foregroundObserverRegistered = false
    }
}
