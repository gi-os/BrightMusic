package com.lightphone.spotify.playback

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * How long the fade between tracks is, in seconds, and where that number is kept.
 *
 * It rides in `phono_playback` — the same small preferences file the resume point uses — rather than
 * a file of its own. That file is already in [com.lightphone.spotify.backup.Backup]'s Settings store,
 * so the setting travels to a restored phone with no change there, and it is one preference about
 * playback sitting beside another.
 *
 * Stored as an Int, not an enum. Nothing here is persisted by `.name`, so R8 full mode has nothing
 * to rename and `proguard-rules.pro` needs no new keep rule.
 */
class TrackFadePreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PlaybackResume.PREFS_NAME, Context.MODE_PRIVATE)

    fun seconds(): Int = TrackFade.sanitize(prefs.getInt(KEY_SECONDS, TrackFade.OFF_SECONDS))

    fun setSeconds(value: Int) {
        prefs.edit().putInt(KEY_SECONDS, TrackFade.sanitize(value)).apply()
    }

    private companion object {
        const val KEY_SECONDS = "track_fade_seconds"
    }
}

/**
 * The live value, observable from Compose the way [com.lightphone.spotify.podcast.PodcastSettings]
 * is: settings screens read it directly, and the playback controller reads it on every tick.
 */
object TrackFadeSettings {
    var seconds: Int by mutableIntStateOf(TrackFade.OFF_SECONDS)
        private set

    fun load(prefs: TrackFadePreferences) {
        seconds = prefs.seconds()
    }

    fun set(prefs: TrackFadePreferences, value: Int) {
        val clean = TrackFade.sanitize(value)
        seconds = clean
        prefs.setSeconds(clean)
    }

    val halfMs: Long get() = TrackFade.halfMs(seconds)
    val enabled: Boolean get() = seconds > 0
}
