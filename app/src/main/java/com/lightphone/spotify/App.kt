package com.lightphone.spotify

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.lightphone.spotify.data.backend.BackendPreferences
import com.lightphone.spotify.playback.PlaybackController
import com.lightphone.spotify.playback.connect.ConnectAliasPreferences
import com.lightphone.spotify.playback.connect.ConnectAliases
import com.lightphone.spotify.data.local.LibraryBackfill
import com.lightphone.spotify.playback.download.OfflinePinHygiene
import com.lightphone.spotify.playback.download.SpotifyDownloadCenter
import com.lightphone.spotify.podcast.PodcastAutoDownload
import com.lightphone.spotify.podcast.PodcastPreferences
import com.lightphone.spotify.podcast.PodcastSettings
import com.lightphone.spotify.ui.light.ArtworkPreferences
import com.lightphone.spotify.ui.light.ArtworkSettings
import com.lightphone.spotify.ui.light.PinnedItems
import com.lightphone.spotify.ui.light.PinnedPreferences
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
        // Pinned playlists and the favourite Bluetooth device, both read by screens that have no
        // ViewModel handle.
        PinnedItems.load(PinnedPreferences(this))
        PodcastSettings.load(PodcastPreferences(this))
        // Read before any device list can be composed, so a renamed speaker never flashes its Spotify
        // name first.
        ConnectAliases.load(ConnectAliasPreferences(this))
        // Upstream phono gated this on a first-run Spotify/TIDAL picker. LightPhono has
        // one backend, so pin the choice and build the controller straight away.
        BackendPreferences(this).ensureSpotify()
        // A download that gives up with "no playable file" has discovered that Spotify holds no audio
        // of its own for that episode — see SpotifyEpisode.isExternallyHosted. Remember it so the row
        // greys out instead of offering the same failure again.
        SpotifyDownloadCenter.onDownloadFailed = { uri, message ->
            if (message.contains("no playable file", ignoreCase = true)) {
                PodcastSettings.markUnplayable(PodcastPreferences(this), uri)
            }
        }
        ensureController()
    }

    /** Build the controller for the persisted backend choice (idempotent). */
    fun ensureController(): PlaybackController {
        controller?.let { return it }
        OfflinePinHygiene.enforce(this)
        // Repair downloads interrupted by the last process death, before any screen reads their
        // state and renders a spinner for them.
        OfflinePinHygiene.requeueInterrupted(this)
        // One-shot: rebuild playlist rows synced before art_url was populated, so covers appear
        // without waiting for Spotify to change something.
        LibraryBackfill.run(this)
        val c = PlaybackController.get(this)
        controller = c
        // Must come after `controller` is set: the checker reads App.controller to reach the Web API
        // and would otherwise bail on every launch. Cheap when no show has auto-download on, and it
        // also re-arms the daily alarm, which a reboot or an app update clears.
        PodcastAutoDownload.checkNow(this)
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
