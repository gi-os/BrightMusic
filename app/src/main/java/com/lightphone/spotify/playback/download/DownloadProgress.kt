package com.lightphone.spotify.playback.download

/**
 * How far along one in-flight download is.
 *
 * Held in memory rather than on the row, because `downloaded_tracks` has no column for a total and
 * adding one means a Room version bump, which `PhonoDatabase` answers with
 * `fallbackToDestructiveMigration()` — every pin on the phone deleted to make room for a number that
 * is only interesting while the transfer is running. Nothing here needs to survive a process death:
 * a download that was interrupted starts again from zero anyway.
 */
data class DownloadProgress(
    val fetchedBytes: Long,
    val totalBytes: Long,
) {
    /** 0..100, or null when the total is not known yet and a bar would be a guess. */
    val percent: Int?
        get() = if (totalBytes <= 0L) null else {
            ((fetchedBytes.coerceAtLeast(0L) * 100L) / totalBytes).toInt().coerceIn(0, 100)
        }

    /** 0f..1f for a determinate bar; null means show an indeterminate one. */
    val fraction: Float?
        get() = percent?.let { it / 100f }
}

/**
 * Decides which of the many progress reports are worth acting on.
 *
 * Rust reports once per 256 KiB chunk, so a 320 kbps four-minute track fires roughly forty times and
 * a long podcast episode several hundred. Writing each one to Room would put a database transaction
 * on the download's critical path for a bar that cannot show the difference, so a report is only let
 * through when the percentage has actually moved by [percentStep] or [minIntervalMs] has passed
 * since the last one — whichever comes first, so a slow chunk still refreshes the screen and a fast
 * one still advances the bar.
 *
 * The first report of a download and the arrival at 100 always pass: the first is what turns a
 * spinner into a bar, and the last is what stops the bar short of the end.
 *
 * Deliberately free of Android imports so it can be unit tested on the host — see
 * `DownloadProgressThrottleTest`.
 */
class DownloadProgressThrottle(
    private val percentStep: Int = DEFAULT_PERCENT_STEP,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
) {
    private val lastPercent = HashMap<String, Int>()
    private val lastAtMs = HashMap<String, Long>()

    fun shouldReport(uri: String, progress: DownloadProgress, nowMs: Long): Boolean {
        val percent = progress.percent ?: return false
        val previous = lastPercent[uri]
        val since = nowMs - (lastAtMs[uri] ?: Long.MIN_VALUE)
        val pass = when {
            previous == null -> true
            percent >= 100 && previous < 100 -> true
            percent - previous >= percentStep -> true
            since >= minIntervalMs -> true
            else -> false
        }
        if (pass) {
            lastPercent[uri] = percent
            lastAtMs[uri] = nowMs
        }
        return pass
    }

    /** Called when a download ends, however it ended, so the maps do not grow for the life of the process. */
    fun forget(uri: String) {
        lastPercent.remove(uri)
        lastAtMs.remove(uri)
    }

    companion object {
        const val DEFAULT_PERCENT_STEP = 2
        const val DEFAULT_MIN_INTERVAL_MS = 500L
    }
}

/**
 * Text for the download notification.
 *
 * Names the track rather than counting bytes: a size means nothing without knowing the total, and
 * the percentage is already the answer to "how much longer". [remaining] is the number of other
 * items still queued behind this one, which is the other thing worth knowing from a glance at the
 * shade.
 */
fun downloadNotificationText(
    title: String?,
    progress: DownloadProgress?,
    remaining: Int,
): String {
    val name = title?.takeIf { it.isNotBlank() } ?: "Preparing…"
    val percent = progress?.percent
    val head = if (percent == null) name else "$name — $percent%"
    return if (remaining > 0) "$head (+$remaining queued)" else head
}
