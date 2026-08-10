package com.gios.light.common.sync

/**
 * What LightSync backs up for a single [FileStore].
 *
 * The Light Phone III's LightSync service ships preference files, SQLite databases, and loose
 * file trees off-device. The Kyocera has no LightSync, so the [Contents] instances the app builds
 * in `Backup` are never read by a sync engine — they only need to compile and carry the right
 * shape so the backup declaration stays intact if the app is ever moved back onto a Light Phone.
 *
 * Each list defaults to empty so a store can name just the categories it needs.
 *
 * @property prefs SharedPreferences file names (without `.xml`).
 * @property databases SQLite database file names.
 * @property files directory or file paths relative to the app's files dir.
 */
data class Contents(
    val prefs: List<String> = emptyList(),
    val databases: List<String> = emptyList(),
    val files: List<String> = emptyList(),
)

/**
 * One named backup target made of a [Contents] payload.
 *
 * Constructed by the app's `Backup` declaration; never consumed on the Kyocera.
 */
data class FileStore(
    val name: String,
    val contents: Contents,
)

/**
 * Base class for an app's LightSync backup declaration.
 *
 * On the Light Phone III the app subclasses this to tell LightSync which stores to ship off-device.
 * The Kyocera has no LightSync, so the base implementations are no-ops and an override like
 * `Backup : LightSyncBackup()` compiles and runs without ever syncing anything.
 */
open class LightSyncBackup {
    /** Human-readable name for this app's backup. No-op default. */
    open fun label(): String = ""

    /** The stores to back up. No-op default — returns nothing. */
    open fun stores(): List<FileStore> = emptyList()
}
