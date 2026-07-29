package com.lightphone.spotify.data.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One-shot repairs for library rows already on disk.
 *
 * `UserPlaylistsSync.refresh()` deliberately short-circuits when the remote total, head id and
 * snapshot all match — that is what keeps a refresh cheap. The consequence is that a bug fix which
 * changes how a row is *mapped* never reaches rows that were synced before the fix, because from the
 * sync's point of view nothing changed.
 *
 * Deleting the sync-state marker is enough: `refresh()` then takes its rebuild path (`sync == null`)
 * and re-inserts every playlist through the current mapper. The playlist table is a cache of the Web
 * API, so rebuilding it costs one request and loses nothing.
 *
 * Deliberately **not** a Room destructive migration, which is the other way to force a rebuild:
 * `PhonoDatabase` uses `fallbackToDestructiveMigration()`, so bumping the version would also wipe
 * `downloaded_tracks` and `downloaded_collections` and take the user's offline music with it.
 *
 * Each entry here is keyed by a flag so it runs exactly once and can never turn into a resync loop.
 */
object LibraryBackfill {
    private const val TAG = "LibraryBackfill"
    private const val PREFS = "phono_library_backfill"

    /** Bumped when a fix needs old playlist rows rebuilt. */
    private const val KEY_PLAYLIST_ART = "playlist_art_v1"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Force one playlist resync so covers appear on rows synced before `art_url` was populated.
     *
     * `SpotifyPlaylistSimple.toEntity` hardcoded `art_url = null`, so every playlist synced by an
     * earlier build has no cover even though the API had one. Safe to call on every cold start.
     */
    fun run(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PLAYLIST_ART, false)) return
        scope.launch {
            runCatching {
                PhonoDatabase.get(app)
                    .librarySyncDao()
                    .delete(LibraryResource.USER_PLAYLISTS)
                Log.i(TAG, "cleared playlist sync state; next refresh will rebuild with covers")
            }.onFailure { Log.w(TAG, "playlist art backfill failed", it) }
            // Marked done either way. A failure here means one build without covers, which is far
            // better than clearing the sync state on every launch forever.
            prefs.edit().putBoolean(KEY_PLAYLIST_ART, true).apply()
        }
    }
}
