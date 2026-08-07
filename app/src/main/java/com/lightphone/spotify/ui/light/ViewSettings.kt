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

    fun load(prefs: ViewPreferences) {
        playlistGrid = prefs.playlistGrid()
        podcastGrid = prefs.podcastGrid()
        defaultTabRoute = prefs.defaultTabRoute()
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

    companion object {
        private const val PREFS_NAME = "phono_view"
        private const val KEY_PLAYLIST_GRID = "playlist_grid"
        private const val KEY_PODCAST_GRID = "podcast_grid"
        private const val KEY_DEFAULT_TAB = "default_tab"
    }
}
