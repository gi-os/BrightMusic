package com.lightphone.spotify.playback

import android.content.Context
import com.lightphone.spotify.data.TrackMetadata

/**
 * The last thing that was playing, so opening the player after a restart offers to continue it.
 *
 * Without this, Now Playing on a cold start said "No song playing / Go back and play something!" —
 * technically true and useless, since the phone had just been paused mid-album. The engine's position
 * is process-lifetime only, so anything that has to survive being killed has to be written down.
 *
 * SharedPreferences, and only one entry: this is a single small fact about the session, and the point
 * is to have it available *before* any library query completes, so the player can render it on the
 * first frame with no network at all.
 */
class PlaybackResume(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Enough to draw the player without a lookup.
     *
     * The metadata is stored rather than just the uri on purpose: resolving a uri to a title needs
     * either the network or a database read, and a player that appears blank and then fills in a
     * second later looks broken.
     */
    data class Saved(
        val track: TrackMetadata,
        val positionMs: Long,
    )

    fun saved(): Saved? {
        val uri = prefs.getString(KEY_URI, null)?.takeIf { it.isNotBlank() } ?: return null
        return Saved(
            track = TrackMetadata(
                uri = uri,
                title = prefs.getString(KEY_TITLE, null).orEmpty(),
                artists = prefs.getString(KEY_ARTIST, null).orEmpty(),
                album = prefs.getString(KEY_ALBUM, null).orEmpty(),
                durationMs = prefs.getLong(KEY_DURATION, 0L),
                artUrl = prefs.getString(KEY_ART, null),
                albumId = prefs.getString(KEY_ALBUM_ID, null),
            ),
            positionMs = prefs.getLong(KEY_POSITION, 0L),
        )
    }

    fun save(track: TrackMetadata, positionMs: Long) {
        prefs.edit()
            .putString(KEY_URI, track.uri)
            .putString(KEY_TITLE, track.title)
            .putString(KEY_ARTIST, track.artists)
            .putString(KEY_ALBUM, track.album)
            .putString(KEY_ART, track.artUrl)
            .putString(KEY_ALBUM_ID, track.albumId)
            .putLong(KEY_DURATION, track.durationMs)
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .apply()
    }

    /** Just the position, for the frequent case where the track has not changed. */
    fun savePosition(uri: String, positionMs: Long) {
        if (prefs.getString(KEY_URI, null) != uri) return
        prefs.edit().putLong(KEY_POSITION, positionMs.coerceAtLeast(0L)).apply()
    }

    /**
     * Forget the resume point, and only that.
     *
     * Keys are removed one at a time rather than `clear()`ing the file: it is shared with the
     * fade-between-tracks length ([TrackFadePreferences]), and a sign-out should not silently reset
     * a playback preference that has nothing to do with the account.
     */
    fun clear() {
        prefs.edit()
            .remove(KEY_URI)
            .remove(KEY_TITLE)
            .remove(KEY_ARTIST)
            .remove(KEY_ALBUM)
            .remove(KEY_ART)
            .remove(KEY_ALBUM_ID)
            .remove(KEY_DURATION)
            .remove(KEY_POSITION)
            .apply()
    }

    internal companion object {
        const val PREFS_NAME = "phono_playback"
        const val KEY_URI = "uri"
        const val KEY_TITLE = "title"
        const val KEY_ARTIST = "artist"
        const val KEY_ALBUM = "album"
        const val KEY_ART = "art_url"
        const val KEY_ALBUM_ID = "album_id"
        const val KEY_DURATION = "duration_ms"
        const val KEY_POSITION = "position_ms"
    }
}
