package com.lightphone.spotify.radio

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's saved stations.
 *
 * SharedPreferences rather than Room, for the same reason podcasts are (see `PodcastSettings`):
 * `PhonoDatabase` uses `fallbackToDestructiveMigration()`, so adding an entity means a version bump
 * that wipes the user's downloaded music. A handful of stations is not worth that risk, and unlike
 * downloads there is nothing relational here — just an ordered list of five fields.
 *
 * Stored as a JSON array under one key rather than a `StringSet`, because order is the user's (they put
 * their daily station at the top) and a set has none.
 */
class RadioPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Saved stations, oldest-added first. Empty on a fresh install — [DefaultStations] is what the tab
     * seeds itself with, and seeding writes here so the user can then remove any of them.
     */
    fun favorites(): List<RadioStation> {
        val raw = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let(::fromJson)
            }
        }.getOrElse {
            Log.w(TAG, "favorites unreadable; starting empty", it)
            emptyList()
        }
    }

    fun setFavorites(stations: List<RadioStation>) {
        val array = JSONArray()
        stations.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    /** True once the NYC starter set has been written, so removing one of them does not bring it back. */
    fun seeded(): Boolean = prefs.getBoolean(KEY_SEEDED, false)

    fun markSeeded() {
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    private fun toJson(station: RadioStation) = JSONObject().apply {
        put("id", station.id)
        put("title", station.title)
        put("url", station.url)
        station.subtitle?.let { put("subtitle", it) }
        station.artworkUrl?.let { put("artwork", it) }
        // Only the mount needs storing: every saved station is a directory station, and NTS entries
        // live in code. Restoring is therefore always IcecastStatus.
        (station.metadata as? RadioStation.MetadataSource.IcecastStatus)?.let { put("mount", it.mount) }
    }

    private fun fromJson(o: JSONObject): RadioStation? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val title = o.optString("title").takeIf { it.isNotBlank() } ?: return null
        val url = o.optString("url").takeIf { it.isNotBlank() } ?: return null
        return RadioStation(
            id = id,
            title = title,
            url = url,
            subtitle = o.optString("subtitle").takeIf { it.isNotBlank() },
            artworkUrl = o.optString("artwork").takeIf { it.isNotBlank() },
            metadata = RadioStation.MetadataSource.IcecastStatus(o.optString("mount")),
            origin = RadioStation.Origin.Directory,
        )
    }

    private companion object {
        const val TAG = "RadioPreferences"
        const val PREFS_NAME = "radio_stations"
        const val KEY_FAVORITES = "favorites"
        const val KEY_SEEDED = "seeded_v1"
    }
}
