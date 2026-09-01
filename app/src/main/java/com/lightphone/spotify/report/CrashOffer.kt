package com.lightphone.spotify.report

import android.app.ApplicationExitInfo

/**
 * Whether a stored crash log is worth putting "IT CRASHED · SEND?" on screen for.
 *
 * Two things went wrong with just asking "does the file exist".
 *
 * **It said crashed about deaths that were not crashes.** The handler writes the file from a dying
 * process, but the app also dies for ordinary reasons — you swipe it away, an update replaces the
 * APK, Android reclaims it in the background — and a log left over from any earlier run made all of
 * those look like a crash to the next launch. The OS knows the difference and will say so:
 * `ActivityManager.getHistoricalProcessExitReasons` records why each process died. So the log names
 * the pid that wrote it, and this asks the OS what happened to that pid.
 *
 * **And it never stopped asking.** The chip clears the log only when it is *tapped*; ignoring it
 * left the file in place, so one crash weeks ago greeted every cold start forever. The chip's own
 * rule is that silence is an answer — this carries that across launches instead of contradicting it.
 * See [CrashLog.takeOffer].
 *
 * Pure so the rules can be tested without an emulator.
 */
object CrashOffer {

    /** One process death, as the OS remembers it. */
    data class Death(val pid: Int, val reason: Int)

    enum class Verdict {
        /** Raise the chip. */
        Offer,

        /** The OS says this death was not a crash, or it is too old to still be news. Drop it. */
        Drop,
    }

    /**
     * Reasons that mean what a person means by "it crashed".
     *
     * These are compile-time constants, so they inline and a plain JUnit test can use them without
     * a device. Deliberately excluded: `REASON_USER_REQUESTED` and `REASON_USER_STOPPED` (you
     * closed it), `REASON_PACKAGE_STATE_CHANGE` and `REASON_PACKAGE_UPDATED` (an update — the case
     * that prompted this), `REASON_LOW_MEMORY` and `REASON_OTHER` (Android reclaimed it in the
     * background), `REASON_EXIT_SELF`, and `REASON_SIGNALED` — a Java handler wrote this file, so a
     * signal is not the story.
     */
    private val CRASHES = setOf(
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
    )

    /**
     * @param pid the process that wrote the log, or null for a log written before the pid was
     *   recorded.
     * @param deaths what the OS remembers, newest first, as it returns them.
     */
    fun decide(pid: Int?, deaths: List<Death>): Verdict = when {
        // No pid to match, or an OS that told us nothing: fall back to trusting the log. Staying
        // quiet about a real crash is the worse failure of the two, and this is the branch every
        // log written by an older build takes exactly once.
        pid == null || deaths.isEmpty() -> Verdict.Offer

        // The death this log came from has aged out of the OS's history, which is bounded. So it
        // is not the last thing that happened to this app — the phone has started and stopped it
        // since — and a crash nobody mentioned at the time is not news now.
        deaths.none { it.pid == pid } -> Verdict.Drop

        else -> if (deaths.first { it.pid == pid }.reason in CRASHES) Verdict.Offer else Verdict.Drop
    }
}
