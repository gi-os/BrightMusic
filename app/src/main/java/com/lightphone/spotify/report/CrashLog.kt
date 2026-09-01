package com.lightphone.spotify.report

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept until somebody has read it.
 *
 * A sideloaded app on a phone with no developer tools to hand is a black box: it either works or
 * it "just closes", and the stack trace — the one piece of information that would settle it in a
 * second — is in a logcat nobody has a cable for. So the handler writes it to a file, and the
 * next launch offers to send it.
 *
 * Nothing is sent from in here. Writing the file is the last thing a dying process does and it
 * has no business opening a socket; the report goes out on the next launch, from a healthy one.
 */
object CrashLog {

    private const val FILE = "last-crash.txt"

    /** Its own file rather than a line in the trace: the trace is evidence and stays untouched. */
    private const val OFFERED = "last-crash-offered"

    /** How far back to ask the OS. It keeps a bounded history; this is well inside it. */
    private const val DEATHS_TO_READ = 16

    private const val PID_PREFIX = "pid: "

    /** Chain onto whatever was already installed rather than replacing it. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            // Always hand on: swallowing it would leave the process wedged instead of dying,
            // which is worse than crashing and is not this object's decision to make.
            previous?.uncaughtException(thread, error)
        }
    }

    /** The stored trace, for attaching to a report somebody chose to send. */
    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    /**
     * The trace, if this launch should offer to send it — and never again after that.
     *
     * Two rules, one per way the old "does the file exist" test was wrong.
     *
     * **It has to have been a crash.** The app dies for ordinary reasons too, and a leftover log
     * made every one of them announce itself as a crash on the next launch. [CrashOffer] asks the
     * OS what happened to the process that wrote this log; a death the OS calls an update, a swipe
     * away, or a background reclaim is dropped, log and all — the OS has spoken and there is
     * nothing left to keep.
     *
     * **And it is offered once.** The chip is cleared only by being tapped, so ignoring it used to
     * re-raise the same crash at every cold start until the end of time. Silence is the chip's
     * answer to its own four-second window; this is the same answer across launches. The trace
     * survives an ignored offer, so shaking the phone and picking "It crashed" still sends it.
     */
    fun takeOffer(context: Context): String? {
        // An Activity is recreated for things that are not launches — a configuration change,
        // "don't keep activities" — and those stay in the same process, so a process-scoped flag
        // draws the line a launch draws.
        if (offeredThisProcess) return null
        offeredThisProcess = true

        val trace = read(context) ?: return null
        if (offeredFile(context).exists()) return null
        runCatching { offeredFile(context).writeText("") }

        val verdict = CrashOffer.decide(pid = pidOf(trace), deaths = deaths(context))
        if (verdict == CrashOffer.Verdict.Drop) {
            clear(context)
            return null
        }
        return trace
    }

    @Volatile
    private var offeredThisProcess = false

    fun clear(context: Context) {
        runCatching { file(context).delete() }
        runCatching { offeredFile(context).delete() }
    }

    /**
     * Why this app's recent processes died, newest first.
     *
     * Wrapped in runCatching because it is a system call on a device this app does not control,
     * and an empty list is a perfectly good answer here — [CrashOffer] reads it as "the OS said
     * nothing" and falls back to trusting the log.
     */
    private fun deaths(context: Context): List<CrashOffer.Death> = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        am.getHistoricalProcessExitReasons(context.packageName, 0, DEATHS_TO_READ)
            .map { CrashOffer.Death(pid = it.pid, reason = it.reason) }
    }.getOrDefault(emptyList())

    private fun pidOf(trace: String): Int? = trace.lineSequence()
        .firstOrNull { it.startsWith(PID_PREFIX) }
        ?.removePrefix(PID_PREFIX)
        ?.trim()
        ?.toIntOrNull()

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        file(context).writeText(
            buildString {
                appendLine("at: $at")
                // The one thing the next launch cannot work out for itself: which process this
                // was, so it can ask the OS how that process ended.
                appendLine("$PID_PREFIX${Process.myPid()}")
                appendLine("thread: ${thread.name}")
                appendLine("screen: ${ReportContext.screen}")
                appendLine()
                append(stack)
            },
        )
        // A new crash is a new question, so it gets a new offer.
        runCatching { offeredFile(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun offeredFile(context: Context) = File(context.filesDir, OFFERED)
}
