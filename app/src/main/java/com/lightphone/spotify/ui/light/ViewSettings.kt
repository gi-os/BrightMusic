package com.lightphone.spotify.ui.light

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-wide observable layout preferences, mirroring [ArtworkSettings]: a
 * `mutableStateOf` per setting so changing one in Settings recomposes every screen
 * that reads it, backed by [ViewPreferences] for persistence.
 */
object ViewSettings {
    /** Playlists tab renders as the 2-across cover grid instead of rows. */
    var playlistGrid: Boolean by mutableStateOf(false)
        private set

    /** Podcasts tab renders as the 2-across cover grid instead of rows. */
    var podcastGrid: Boolean by mutableStateOf(false)
        private set

    /** Route of the tab the app opens on. Must be one of the bar tabs. */
    var defaultTabRoute: String by mutableStateOf(DEFAULT_TAB_ROUTE)
        private set

    /**
     * A drag inwards from the left edge goes back.
     *
     * **On, because this phone has no back button and no navigation bar.** An app that pushes a
     * screen and offers no way out of it is a dead end until you press home, so the gesture is a
     * property of the app rather than a preference to discover.
     *
     * A toggle because it is no longer the only thing that owns that edge. BrightControl can put a
     * strip down the left side of the screen that goes back for *every* app, and where it is on,
     * this one is the second of two gestures doing the same job on the same edge — the outer strip
     * takes the touch, this never sees it, and the only symptom is a swipe that works some of the
     * time depending on how far from the edge a thumb landed. Turning this off leaves one gesture
     * with one owner. It is also the switch for anyone whose thumb keeps going back out of a list
     * they were trying to scroll.
     */
    var swipeBack: Boolean by mutableStateOf(true)
        private set

    fun load(prefs: ViewPreferences) {
        playlistGrid = prefs.playlistGrid()
        podcastGrid = prefs.podcastGrid()
        defaultTabRoute = prefs.defaultTabRoute()
        swipeBack = prefs.swipeBack()
    }

    fun setPlaylistGrid(prefs: ViewPreferences, value: Boolean) {
        playlistGrid = value
        prefs.setPlaylistGrid(value)
    }

    fun setPodcastGrid(prefs: ViewPreferences, value: Boolean) {
        podcastGrid = value
        prefs.setPodcastGrid(value)
    }

    fun setDefaultTabRoute(prefs: ViewPreferences, value: String) {
        defaultTabRoute = value
        prefs.setDefaultTabRoute(value)
    }

    fun setSwipeBack(prefs: ViewPreferences, value: Boolean) {
        swipeBack = value
        prefs.setSwipeBack(value)
    }

    const val DEFAULT_TAB_ROUTE = "playlists"
}

class ViewPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun playlistGrid(): Boolean = prefs.getBoolean(KEY_PLAYLIST_GRID, false)
    fun setPlaylistGrid(value: Boolean) = prefs.edit().putBoolean(KEY_PLAYLIST_GRID, value).apply()

    fun podcastGrid(): Boolean = prefs.getBoolean(KEY_PODCAST_GRID, false)
    fun setPodcastGrid(value: Boolean) = prefs.edit().putBoolean(KEY_PODCAST_GRID, value).apply()

    fun defaultTabRoute(): String =
        prefs.getString(KEY_DEFAULT_TAB, null) ?: ViewSettings.DEFAULT_TAB_ROUTE

    fun setDefaultTabRoute(value: String) = prefs.edit().putString(KEY_DEFAULT_TAB, value).apply()

    /** Defaults true: see [ViewSettings.swipeBack]. */
    fun swipeBack(): Boolean = prefs.getBoolean(KEY_SWIPE_BACK, true)
    fun setSwipeBack(value: Boolean) = prefs.edit().putBoolean(KEY_SWIPE_BACK, value).apply()

    companion object {
        private const val PREFS_NAME = "phono_view"
        private const val KEY_PLAYLIST_GRID = "playlist_grid"
        private const val KEY_PODCAST_GRID = "podcast_grid"
        private const val KEY_DEFAULT_TAB = "default_tab"
        private const val KEY_SWIPE_BACK = "swipe_back"
    }
}
