package com.lightphone.spotify.ui.light

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Playlists and shows pinned to the top of their list, and the favourite Bluetooth device.
 *
 * Two unrelated preferences in one file because they are the same shape — a tiny, non-secret user
 * choice that several screens read and that has to survive a restart. Both live in plain
 * SharedPreferences rather than Room: neither is worth a migration, and losing them would be an
 * annoyance rather than data loss.
 *
 * Observable via Compose state so pinning a playlist reorders the list immediately instead of on the
 * next sync, in the same way [ArtworkSettings] drives covers.
 */
object PinnedItems {

    /** Playlist ids, most recently pinned first. */
    var pinnedPlaylists: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * Show ids, most recently pinned first.
     *
     * A separate list rather than one pool keyed by uri: the two are shown on different screens and a
     * show id and a playlist id could in principle collide, which would make pinning one silently pin
     * the other.
     */
    var pinnedShows: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * MAC address of the Bluetooth device a long-press on the player's cast control connects to.
     * Null until one is chosen.
     */
    var favouriteBluetooth: String? by mutableStateOf(null)
        private set

    /** Human-readable name for the favourite, so the UI can name it without a Bluetooth query. */
    var favouriteBluetoothName: String? by mutableStateOf(null)
        private set

    fun load(prefs: PinnedPreferences) {
        pinnedPlaylists = prefs.pinnedPlaylists()
        pinnedShows = prefs.pinnedShows()
        favouriteBluetooth = prefs.favouriteBluetooth()
        favouriteBluetoothName = prefs.favouriteBluetoothName()
    }

    fun isPinned(playlistId: String): Boolean = playlistId in pinnedPlaylists

    fun togglePinned(prefs: PinnedPreferences, playlistId: String) {
        // Newly pinned goes to the front: the thing you just pinned is the thing you want to see.
        pinnedPlaylists = if (playlistId in pinnedPlaylists) {
            pinnedPlaylists - playlistId
        } else {
            listOf(playlistId) + pinnedPlaylists
        }
        prefs.setPinnedPlaylists(pinnedPlaylists)
    }

    fun isShowPinned(showId: String): Boolean = showId in pinnedShows

    fun toggleShowPinned(prefs: PinnedPreferences, showId: String) {
        pinnedShows = if (showId in pinnedShows) {
            pinnedShows - showId
        } else {
            listOf(showId) + pinnedShows
        }
        prefs.setPinnedShows(pinnedShows)
    }

    fun setFavouriteBluetooth(prefs: PinnedPreferences, address: String?, name: String?) {
        favouriteBluetooth = address
        favouriteBluetoothName = name
        prefs.setFavouriteBluetooth(address, name)
    }

    /**
     * Pinned first, in pin order, then everything else untouched.
     *
     * Stable for anything unpinned, so a pin does not otherwise disturb whatever order the library
     * sync produced.
     */
    fun <T> sortPinnedFirst(items: List<T>, idOf: (T) -> String): List<T> =
        sortByPinOrder(pinnedPlaylists, items, idOf)

    fun <T> sortPinnedShowsFirst(items: List<T>, idOf: (T) -> String): List<T> =
        sortByPinOrder(pinnedShows, items, idOf)

    private fun <T> sortByPinOrder(order: List<String>, items: List<T>, idOf: (T) -> String): List<T> {
        if (order.isEmpty()) return items
        val rank = order.withIndex().associate { (i, id) -> id to i }
        return items.sortedBy { rank[idOf(it)] ?: Int.MAX_VALUE }
    }
}

class PinnedPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun pinnedPlaylists(): List<String> =
        prefs.getString(KEY_PINNED, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun setPinnedPlaylists(ids: List<String>) {
        // Newline-separated: a Spotify id cannot contain one, and it keeps order, which a StringSet
        // would not.
        prefs.edit().putString(KEY_PINNED, ids.joinToString("\n")).apply()
    }

    fun pinnedShows(): List<String> =
        prefs.getString(KEY_PINNED_SHOWS, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun setPinnedShows(ids: List<String>) {
        prefs.edit().putString(KEY_PINNED_SHOWS, ids.joinToString("\n")).apply()
    }

    fun favouriteBluetooth(): String? = prefs.getString(KEY_FAV_BT, null)

    fun favouriteBluetoothName(): String? = prefs.getString(KEY_FAV_BT_NAME, null)

    fun setFavouriteBluetooth(address: String?, name: String?) {
        prefs.edit()
            .putString(KEY_FAV_BT, address)
            .putString(KEY_FAV_BT_NAME, name)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "phono_pinned"
        const val KEY_PINNED = "pinned_playlists"
        const val KEY_PINNED_SHOWS = "pinned_shows"
        const val KEY_FAV_BT = "favourite_bluetooth"
        const val KEY_FAV_BT_NAME = "favourite_bluetooth_name"
    }
}
