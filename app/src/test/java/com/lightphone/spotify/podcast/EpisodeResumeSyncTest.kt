package com.lightphone.spotify.podcast

import com.lightphone.spotify.podcast.EpisodeResumeSync.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cross-device rule: adopt Spotify's resume point only when it moved since we last looked.
 */
class EpisodeResumeSyncTest {

    private val twenty = RemoteResume(20 * 60_000, fullyPlayed = false)

    @Test
    fun `nothing from Spotify keeps the local position`() {
        assertEquals(Outcome.Keep, EpisodeResumeSync.decide(remote = null, lastSeen = twenty))
        assertEquals(Outcome.Keep, EpisodeResumeSync.decide(remote = null, lastSeen = null))
    }

    @Test
    fun `an unchanged remote point keeps the local position`() {
        // The whole point: everything this phone listened to since the last look — on a train, with
        // no signal — is ahead of a remote value that has not moved.
        assertEquals(Outcome.Keep, EpisodeResumeSync.decide(remote = twenty, lastSeen = twenty))
    }

    @Test
    fun `a remote point that moved forward is adopted`() {
        val later = RemoteResume(35 * 60_000, fullyPlayed = false)
        assertEquals(
            Outcome.Adopt(35 * 60_000, fullyPlayed = false),
            EpisodeResumeSync.decide(remote = later, lastSeen = twenty),
        )
    }

    @Test
    fun `a remote point that moved backward is adopted too`() {
        // Scrubbing back on the desktop to re-hear something is a deliberate act, and a
        // furthest-wins rule would ignore it.
        val earlier = RemoteResume(5 * 60_000, fullyPlayed = false)
        assertEquals(
            Outcome.Adopt(5 * 60_000, fullyPlayed = false),
            EpisodeResumeSync.decide(remote = earlier, lastSeen = twenty),
        )
    }

    @Test
    fun `finishing it elsewhere is adopted`() {
        val done = RemoteResume(0, fullyPlayed = true)
        assertEquals(
            Outcome.Adopt(0, fullyPlayed = true),
            EpisodeResumeSync.decide(remote = done, lastSeen = twenty),
        )
    }

    @Test
    fun `an unstarted episode seen for the first time changes nothing`() {
        // What Spotify reports for every episode nobody has opened. Adopting it on the first list
        // load after the scope is granted would wipe every local position on the phone.
        val unstarted = RemoteResume(0, fullyPlayed = false)
        assertEquals(Outcome.Keep, EpisodeResumeSync.decide(remote = unstarted, lastSeen = null))
        assertEquals(
            Outcome.Keep,
            EpisodeResumeSync.decide(
                remote = RemoteResume(EpisodeResume.RESUME_FLOOR_MS, fullyPlayed = false),
                lastSeen = null,
            ),
        )
    }

    @Test
    fun `a real position seen for the first time is adopted`() {
        assertEquals(
            Outcome.Adopt(20 * 60_000, fullyPlayed = false),
            EpisodeResumeSync.decide(remote = twenty, lastSeen = null),
        )
    }

    @Test
    fun `the stored form round-trips`() {
        assertEquals(twenty, RemoteResume.decode(twenty.encode()))
        val done = RemoteResume(0, fullyPlayed = true)
        assertEquals(done, RemoteResume.decode(done.encode()))
    }

    @Test
    fun `a missing or corrupt stored form reads as never seen`() {
        // Never-seen and unreadable have to answer the same, or a bad write turns into an adopt
        // that wipes a local position.
        assertNull(RemoteResume.decode(null))
        assertNull(RemoteResume.decode(""))
        assertNull(RemoteResume.decode("1200000"))
        assertNull(RemoteResume.decode("abc:false"))
        assertNull(RemoteResume.decode("1200000:maybe"))
    }
}
