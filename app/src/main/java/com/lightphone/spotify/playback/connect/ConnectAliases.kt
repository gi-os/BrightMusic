package com.lightphone.spotify.playback.connect

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Local names for Spotify Connect devices.
 *
 * Renaming is deliberately **local only**. Spotify has no endpoint to rename a device — the name in
 * `/me/player/devices` is whatever the device reports about itself, and for a receiver that means
 * whatever its firmware was configured with. So this is an overlay: the alias is stored here and shown
 * instead, and the device is untouched. Nothing is sent anywhere, which also means it works offline and
 * cannot fail.
 *
 * Keyed by Spotify's device id rather than the reported name, so renaming "Living Room" does not also
 * rename a second speaker that happens to share the name, and so an alias survives the device changing
 * its own name later. Devices Spotify reports with a null id cannot be aliased — there is nothing stable
 * to key on — which is the same reason they cannot be targeted.
 *
 * Observable Compose state so a rename shows up immediately rather than on the next device poll, in the
 * same way [com.lightphone.spotify.ui.light.PinnedItems] drives the playlist order.
 */
object ConnectAliases {

    /** Device id → the name the user gave it. */
    var aliases: Map<String, String> by mutableStateOf(emptyMap())
        private set

    fun load(prefs: ConnectAliasPreferences) {
        aliases = prefs.aliases()
    }

    /**
     * What to call this device.
     *
     * [reported] is Spotify's own name, used when there is no alias — and when there is no id, since an
     * alias needs somewhere to live.
     */
    fun nameFor(deviceId: String?, reported: String): String {
        if (deviceId == null) return reported
        return aliases[deviceId]?.takeIf { it.isNotBlank() } ?: reported
    }

    /** Whether this device is showing a name the user chose. */
    fun isRenamed(deviceId: String?): Boolean = deviceId != null && aliases.containsKey(deviceId)

    /**
     * Set or clear an alias. A blank name clears it, so the way back to Spotify's own name is to submit
     * an empty field rather than having to remember what it used to be.
     */
    fun setAlias(prefs: ConnectAliasPreferences, deviceId: String, name: String) {
        val trimmed = name.trim()
        aliases = if (trimmed.isEmpty()) aliases - deviceId else aliases + (deviceId to trimmed)
        prefs.setAliases(aliases)
    }
}

class ConnectAliasPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun aliases(): Map<String, String> =
        prefs.getString(KEY_ALIASES, null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                // id and name are separated by a tab: a Spotify device id is hex and cannot contain
                // one, and a name typed on a phone keyboard cannot either.
                val at = line.indexOf('\t')
                if (at <= 0 || at == line.lastIndex) return@mapNotNull null
                line.substring(0, at) to line.substring(at + 1)
            }
            ?.toMap()
            .orEmpty()

    fun setAliases(values: Map<String, String>) {
        // Newline-delimited rather than a StringSet, which has no order and would reshuffle the file on
        // every write for no reason.
        val encoded = values.entries.joinToString("\n") { (id, name) -> "$id\t$name" }
        prefs.edit().putString(KEY_ALIASES, encoded).apply()
    }

    private companion object {
        const val PREFS_NAME = "phono_connect_aliases"
        const val KEY_ALIASES = "device_aliases"
    }
}
