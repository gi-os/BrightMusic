package com.lightphone.spotify.report

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * A load that never landed files its own report.
 *
 * This is the one failure in the app with no visible edge. A crash has a trace, a failed request
 * has a status line, and both arrive. A spinner over a downloaded track has neither: the screen
 * looks like the app is working, so nobody shakes the phone, and by the time it is described in
 * words — "my downloads don't play on the subway" — every fact that would have separated the four
 * or five mechanisms behind it is gone. Three rounds of this bug were diagnosed by reasoning from
 * that one sentence. This is the fourth, and it brings the facts with it.
 *
 * **What it captures is chosen to tell the candidates apart**, not to be complete. Given a report,
 * exactly one of these should be readable off it:
 *
 * - the file is on disk and the engine still refused it (`downloaded=yes`, `handoff=nothing`)
 * - nothing was downloaded and there was no data either (`downloaded=no`, `network=offline`)
 * - the engine had no answer at all, because a rebuild was holding the lock (`handoff=no answer`)
 * - the load was issued and the player never said another word (`pulse=no`, `playUris` returned)
 * - the transport call itself never returned (`transport=in flight`)
 *
 * ### Throttling
 *
 * Every rule here was learned by flooding the tracker with a variant of the same issue.
 *
 * - **Once per fault family per install, remembered on disk.** A per-process latch treats every
 *   relaunch as a first offence, and a stall is exactly the thing a person retries across
 *   restarts. Thirty issues, one bug.
 * - **The family key holds only the facts that change the diagnosis** — never the uri, never a
 *   duration, never a message. A key the failure can vary is not a key, and that is how one dead
 *   socket became nine "first" offences once before.
 * - **A hard floor between any two sends**, whatever they say, so a family key that turns out to
 *   be accidentally unique still cannot flood.
 * - **The build is part of the key**, so a family goes quiet for the version it was filed against
 *   and speaks up again on the next one. Without that, one uninformative report would be the last
 *   this install ever sent, and a fix that did not work would look exactly like a fix that did.
 *
 * A shake still files freely: that report carries the user's own words, which is the one thing
 * this cannot collect.
 */
object StallReport {

    /** Whatever the key says, no two reports from this object closer together than this. */
    private const val FLOOR_MS = 60_000L

    private const val LEDGER = "stall-reports-filed"

    @Volatile
    private var lastSentAtMs = 0L

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            // Reporting a stall must never be the thing that ends the process.
            CoroutineExceptionHandler { _, _ -> },
    )

    /** What the app knew about the stall, in the words the issue will be read in. */
    data class Facts(
        /** How long the spinner had been up when it was given up on. */
        val spunForMs: Long,
        /** The engine's own answer to "is this track on disk", not Room's. */
        val downloaded: Boolean,
        /** Kotlin's view of connectivity — the flag that decides which path playback takes. */
        val networkOnline: Boolean,
        /** What asking the engine for downloaded audio produced. */
        val handoff: String,
        /** Whether any player event had been seen for this load. */
        val sawPlaybackPulse: Boolean,
        /** Whether the FFI play call had returned by now. */
        val transportInFlight: Boolean,
        /** Track kind only — an episode and a track take different paths. Never the uri. */
        val kind: String,
        /** Whether the engine believes it holds a live session. */
        val sessionConnected: Boolean,
    ) {
        /**
         * The fault family. Only the booleans that change the diagnosis go in — not the duration,
         * not the kind, and above all not the uri, which varies per track and would make every
         * stalled song its own "first" offence.
         */
        internal fun key(): String =
            "stall:d=$downloaded:n=$networkOnline:h=$handoff:p=$sawPlaybackPulse:t=$transportInFlight"

        internal fun what(): String = when {
            downloaded && handoff == "nothing downloaded" ->
                "play a downloaded track the engine says it has no copy of"
            downloaded -> "start a downloaded track"
            !networkOnline -> "start a track with nothing downloaded and no connection"
            else -> "finish loading a track"
        }

        internal fun detail(): String = buildString {
            appendLine("The spinner was up for ${spunForMs / 1000}s with no audio, and was given up on.")
            appendLine()
            appendLine("downloaded (engine)  = ${yesNo(downloaded)}")
            appendLine("network online       = ${yesNo(networkOnline)}")
            appendLine("session connected    = ${yesNo(sessionConnected)}")
            appendLine("handoff to local     = $handoff")
            appendLine("player event seen    = ${yesNo(sawPlaybackPulse)}")
            appendLine("play call returned   = ${yesNo(!transportInFlight)}")
            append("track kind           = $kind")
        }

        private fun yesNo(b: Boolean) = if (b) "yes" else "no"
    }

    /**
     * File this stall if it is a family that has not been filed from this install before.
     *
     * Never throws and never blocks the caller: it is called from the playback watchdog, which is
     * the one loop that must keep running while everything else is wedged.
     */
    fun submit(context: Context, facts: Facts) {
        runCatching { submitOrThrow(context.applicationContext, facts) }
    }

    private fun buildStamp(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun submitOrThrow(app: Context, facts: Facts) {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if (lastSentAtMs != 0L && now - lastSentAtMs < FLOOR_MS) return
            val key = buildStamp(app) + "|" + facts.key()
            if (alreadyFiled(app, key)) return
            markFiled(app, key)
            lastSentAtMs = now
        }
        Reports.enqueue(
            app,
            Reports.compose(
                context = app,
                symptom = Symptom.Froze,
                note = "Playback sat loading and never started.",
                screen = ReportContext.screen,
                crash = null,
                shot = null,
                failure = Failure(what = facts.what(), detail = facts.detail()),
            ),
        )
        scope.launch { runCatching { Reports.flush(app) } }
    }

    private fun alreadyFiled(context: Context, key: String): Boolean =
        runCatching { ledger(context).readLines().contains(key) }.getOrDefault(false)

    private fun markFiled(context: Context, key: String) {
        runCatching { ledger(context).appendText(key + "\n") }
    }

    private fun ledger(context: Context) = File(context.filesDir, LEDGER)
}
