package com.lightphone.spotify.history

import android.content.Context
import java.io.File

/**
 * What you listened to, and when.
 *
 * Kept so a journal can say it. Nothing in this app needs a history — the player knows what is
 * playing now and the resume store knows where you were in it — so this exists entirely to be handed
 * to [PlayHistoryProvider], and it is written to be cheap enough that carrying it for someone else
 * is not a cost worth thinking about.
 *
 * **A day per file, appended, no database.** A row is four short strings and this is written once
 * every few minutes at most; a Room table would mean a schema, a migration and a DAO to record that
 * a song started. Appending a line to today's file is the whole of it, and reading a day back is
 * opening one file whose name you already know.
 *
 * Trimmed on write rather than never: a year of listening is a few hundred kilobytes, which is
 * nothing, but a folder that only grows is a folder that eventually surprises someone.
 */
class PlayHistory(private val context: Context) {

    /** One thing you listened to. Started, not finished — a skip is still a thing that happened. */
    data class Play(val atMs: Long, val title: String, val artist: String, val uri: String)

    /**
     * Note that a track started.
     *
     * Ignores a repeat of the track already at the end of today: `onTrackChanged` can fire more than
     * once for the same track — a seek to zero, a re-buffer — and a day that lists the same song
     * eleven times in a row is describing the player's internals rather than your afternoon.
     */
    fun record(title: String, artist: String, uri: String, atMs: Long = System.currentTimeMillis()) {
        if (title.isBlank() && artist.isBlank()) return
        runCatching {
            val file = fileFor(dayOf(atMs))
            if (file.isFile) {
                val last = file.readLines().lastOrNull()?.let(::parse)
                if (last != null && last.uri == uri) return
            }
            file.parentFile?.mkdirs()
            file.appendText(listOf(atMs.toString(), title.clean(), artist.clean(), uri.clean()).joinToString(SEP) + "\n")
            trim()
        }
    }

    /** A day's listening, earliest first. */
    fun on(day: String): List<Play> = runCatching {
        val file = fileFor(day)
        if (!file.isFile) return emptyList()
        file.readLines().mapNotNull(::parse).sortedBy { it.atMs }
    }.getOrDefault(emptyList())

    private fun parse(line: String): Play? {
        val parts = line.split(SEP)
        if (parts.size < 4) return null
        val at = parts[0].toLongOrNull() ?: return null
        return Play(at, parts[1], parts[2], parts[3])
    }

    /**
     * A tab, and every field has them stripped.
     *
     * A track called "Us and Them, Pt. 2" would break a comma-separated line, and quoting is a
     * parser. A tab cannot appear in a Spotify title, and stripping is one call.
     */
    private fun String.clean() = replace(SEP, " ").replace("\n", " ").trim()

    private fun fileFor(day: String) = File(dir(), "$day.tsv")

    private fun dir() = File(context.filesDir, DIR).apply { mkdirs() }

    private fun trim() {
        val files = dir().listFiles()?.sortedBy { it.name } ?: return
        if (files.size <= KEEP_DAYS) return
        files.take(files.size - KEEP_DAYS).forEach { it.delete() }
    }

    companion object {
        const val DIR = "play-history"
        const val SEP = "\t"

        /** Two years. Long enough for "on this day", short enough to never be a problem. */
        const val KEEP_DAYS = 730

        /**
         * The day a moment belongs to, as `YYYY-MM-DD` **local**.
         *
         * Local, and the file name is the key: the reader asks for a date, and a date only means
         * anything in a time zone. The journal reading this applies its own idea of when a day ends
         * — it treats the small hours as the night before — so what is served is plainly the
         * calendar date and the caller decides what to do with it.
         */
        fun dayOf(atMs: Long): String {
            val date = java.time.Instant.ofEpochMilli(atMs)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            return date.toString()
        }
    }
}
