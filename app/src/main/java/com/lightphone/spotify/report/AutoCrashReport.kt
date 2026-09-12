package com.lightphone.spotify.report

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A crash loop reports itself.
 *
 * The chip is the right way to send a crash a person noticed: they saw it, they choose. It is the
 * wrong way to send a crash that closes the app before the chip finishes drawing. That is the case
 * this exists for, and it is the case where the trace matters most — three "It closed itself"
 * reports were raised by hand against v0.67 and none against v0.68 or v0.69, not because those
 * builds were better but because nobody can tap a four-second offer on an app that is gone in two.
 *
 * So: if the app died a death the OS calls a crash, the trace goes into the same queue the chip
 * writes to, on the next launch, without asking. [Reports.enqueue] never throws and the queue is
 * on disk, so a launch that dies again loses nothing — the post is simply retried by the launch
 * after it.
 *
 * **Once per distinct trace.** The stamp of the trace that was sent is remembered, so a loop that
 * relaunches twenty times files one issue and not twenty. A *different* crash has a different
 * stamp and is sent on its own. This is the whole rate limit; it needs no other.
 *
 * It does not consume the trace. The chip's own offer is a separate decision with its own file
 * ([CrashLog.takeOffer]), and a person who wants to add "it does it every time I open it" should
 * still be able to.
 */
object AutoCrashReport {

    private const val SENT = "last-crash-auto-sent"

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            // A SupervisorJob keeps siblings alive; it does not stop an uncaught throw from
            // reaching the thread's handler and killing the process. Reporting a crash must
            // never be the thing that causes the next one.
            CoroutineExceptionHandler { _, _ -> },
    )

    /**
     * Queue the stored trace if it came from a crash and has not been sent yet, then try to post.
     *
     * Called from [com.lightphone.spotify.App.onCreate], so it runs on every launch including the
     * ones that never reach a screen.
     */
    fun submit(context: Context) {
        val app = context.applicationContext
        val trace = runCatching { CrashLog.takeAuto(app) }.getOrNull() ?: return
        runCatching {
            Reports.enqueue(
                app,
                Reports.compose(
                    context = app,
                    symptom = Symptom.Crashed,
                    note = "Filed automatically on the next launch — nobody was asked.",
                    // The handler stamped the screen into the trace at the moment it died. The
                    // live value here is whatever this *new* process has reached, which is not
                    // where the crash happened, so it must not be used.
                    screen = CrashLog.screenOf(trace) ?: ReportContext.STARTING,
                    crash = trace,
                    shot = null,
                ),
            )
        }
        scope.launch { runCatching { Reports.flush(app) } }
    }

    internal fun markSent(context: Context, stamp: String) {
        runCatching { file(context).writeText(stamp) }
    }

    internal fun alreadySent(context: Context, stamp: String): Boolean =
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull() == stamp

    private fun file(context: Context) = java.io.File(context.filesDir, SENT)
}
