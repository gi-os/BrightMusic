package com.lightphone.spotify.playback.download

import android.content.Context
import android.util.Log
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.playback.PlaybackEngineHolder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reconciles what Room says is downloaded with what is actually on disk.
 *
 * These are two different truths and nothing was checking them against each other. Room's
 * `downloaded_tracks` table is what the app draws — the Downloads screen, the filled pin icon, the
 * "keep offline" counts — while the *player* asks the filesystem, through `downloads::is_downloaded`,
 * which looks for `{base62}_{QUALITY}.ogg` in `spotify-downloads/`. When a row says COMPLETED and no
 * file answers to it, every screen says the album is offline and playback quietly streams it.
 *
 * On a train that is the difference between an album that plays and one that plays a single track and
 * stops: the first track comes out of the streaming LRU cache, and when it ends the queue looks for
 * the next *downloaded* entry, finds none, and pauses.
 *
 * A row can lose its file without anything going wrong in the app: a write interrupted by process
 * death, a wipe from [OfflinePinHygiene], an OS storage sweep, or a restore that brought the database
 * back without the audio. So this is not a bug being papered over — it is a reconciliation that had no
 * owner.
 *
 * The file check goes through the engine, deliberately. Reimplementing "is this downloaded?" in Kotlin
 * would create a third answer to the question, which is the disease rather than the cure.
 */
object PinAudit {
    private const val TAG = "PinAudit"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** What the last audit found, for the settings readout. */
    @Volatile
    var lastResult: Result? = null
        private set

    data class Result(
        val rowsCompleted: Int,
        val filesOnDisk: Int,
        val missingFiles: Int,
        val bytesOnDisk: Long,
    ) {
        /** One line, for Settings. Terse on purpose: it is a readout, not a sentence. */
        fun summary(): String =
            "pins=$filesOnDisk (${bytesOnDisk / (1024 * 1024)} MB) rows=$rowsCompleted missing=$missingFiles"
    }

    /**
     * Check every completed row, and re-queue the ones whose audio is gone.
     *
     * Re-queued rather than deleted: the user asked for that track offline, and QUEUED is what the
     * download service drains, so it comes back by itself the next time there is a connection.
     * Deleting the row would silently drop the request instead.
     */
    fun run(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { audit(app) }
                .onFailure { Log.e(TAG, "audit failed", it) }
        }
    }

    private suspend fun audit(app: Context) {
        val engine = PlaybackEngineHolder.engineOrNull() ?: run {
            Log.i(TAG, "no engine yet; skipping audit")
            return
        }
        val dao = PhonoDatabase.get(app).downloadedTrackDao()
        val completed = dao.getAll().filter { it.state == DownloadStates.COMPLETED }
        val missing = completed.filter { !engine.isTrackDownloaded(it.uri) }
        val now = System.currentTimeMillis()
        for (row in missing) {
            // Bytes reset to zero: the row is about to be fetched again from nothing, and leaving the
            // old byte count would show a full progress bar for a download that has not started.
            dao.updateState(row.uri, DownloadStates.QUEUED, 0L, now)
        }
        val dir = File(engine.downloadsDirectory())
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".ogg") }?.toList().orEmpty()
        lastResult = Result(
            rowsCompleted = completed.size,
            filesOnDisk = files.size,
            missingFiles = missing.size,
            bytesOnDisk = files.sumOf { it.length() },
        )
        if (missing.isEmpty()) {
            Log.i(TAG, "audit clean: ${lastResult?.summary()}")
            return
        }
        Log.w(TAG, "re-queued ${missing.size} download(s) with no audio on disk")
        // Only worth waking the downloader if there is something it can do about it now.
        SpotifyDownloadCenter.resumeDownloads(app)
    }
}
