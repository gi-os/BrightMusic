package com.lightphone.spotify.history

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * What you listened to on a day, offered to the rest of the collection.
 *
 * Read-only, one row per track, by date. It exists for LightNotebook's journal: what you had on is
 * part of a day in the way a photograph is, and this app is the only thing that knows it.
 *
 * `content://com.lightphone.spotify.plays/plays/2026-07-30`
 */
class PlayHistoryProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context ?: return cursor
        val day = uri.lastPathSegment.orEmpty()
        // Checked before use: exported, and the segment becomes part of a file name.
        if (!DAY.matches(day)) return cursor

        PlayHistory(context).on(day).forEach { play ->
            // Explicitly Any?, or Kotlin infers the intersection of Long and String and warns
            // about reifying it. A cursor row is a heterogeneous list by definition.
            cursor.addRow(arrayOf<Any?>(play.atMs, play.title, play.artist, play.uri))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.lightphone.play"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        val COLUMNS = arrayOf("at_ms", "title", "artist", "uri")
        val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    }
}
