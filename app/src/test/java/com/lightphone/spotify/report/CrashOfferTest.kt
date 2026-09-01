package com.lightphone.spotify.report

import android.app.ApplicationExitInfo
import com.lightphone.spotify.report.CrashOffer.Death
import com.lightphone.spotify.report.CrashOffer.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pinned because the bug this fixes was the app calling every ordinary shutdown a crash, and then
 * saying so at every cold start for weeks.
 */
class CrashOfferTest {

    private val crashed = Death(pid = 4242, reason = ApplicationExitInfo.REASON_CRASH)

    @Test
    fun `a crash the OS agrees was a crash is offered`() {
        assertEquals(Verdict.Offer, CrashOffer.decide(pid = 4242, deaths = listOf(crashed)))
    }

    @Test
    fun `a native crash and an ANR count too`() {
        assertEquals(
            Verdict.Offer,
            CrashOffer.decide(4242, listOf(Death(4242, ApplicationExitInfo.REASON_CRASH_NATIVE))),
        )
        assertEquals(
            Verdict.Offer,
            CrashOffer.decide(4242, listOf(Death(4242, ApplicationExitInfo.REASON_ANR))),
        )
    }

    @Test
    fun `an update is not a crash`() {
        // The case that prompted this: reinstalling the APK kills the process, and a log left over
        // from an earlier run made the next launch announce a crash that had not happened.
        assertEquals(
            Verdict.Drop,
            CrashOffer.decide(
                4242,
                listOf(Death(4242, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE)),
            ),
        )
    }

    @Test
    fun `closing it or having it reclaimed is not a crash`() {
        listOf(
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_OTHER,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_SIGNALED,
        ).forEach { reason ->
            assertEquals(
                "reason $reason should not read as a crash",
                Verdict.Drop,
                CrashOffer.decide(4242, listOf(Death(4242, reason))),
            )
        }
    }

    @Test
    fun `a death too old for the OS to remember is dropped`() {
        // The staleness test. The history is bounded, so a pid missing from a non-empty list means
        // the phone has started and stopped this app since — old news, not this launch's business.
        assertEquals(
            Verdict.Drop,
            CrashOffer.decide(4242, listOf(Death(9001, ApplicationExitInfo.REASON_USER_REQUESTED))),
        )
    }

    @Test
    fun `the right pid is matched out of several deaths`() {
        val deaths = listOf(
            Death(9001, ApplicationExitInfo.REASON_USER_REQUESTED),
            crashed,
            Death(1111, ApplicationExitInfo.REASON_LOW_MEMORY),
        )
        assertEquals(Verdict.Offer, CrashOffer.decide(4242, deaths))
        assertEquals(Verdict.Drop, CrashOffer.decide(1111, deaths))
    }

    @Test
    fun `nothing to match on falls back to trusting the log`() {
        // A log from a build before the pid was recorded, and a device whose OS returned nothing.
        // Staying quiet about a real crash is the worse failure of the two.
        assertEquals(Verdict.Offer, CrashOffer.decide(pid = null, deaths = listOf(crashed)))
        assertEquals(Verdict.Offer, CrashOffer.decide(pid = null, deaths = emptyList()))
        assertEquals(Verdict.Offer, CrashOffer.decide(pid = 4242, deaths = emptyList()))
    }
}
