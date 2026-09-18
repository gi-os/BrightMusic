package com.lightphone.spotify.podcast

/**
 * Spotify's own resume point for an episode, as it arrived from the Web API.
 *
 * It used to be written only by Spotify's *other* clients, which made any change to it mean "you
 * listened somewhere else" by construction. Since v0.74 this phone writes it too (see
 * [ResumeReport] and `rust/spotify-core/src/resume.rs`), so the rule below can no longer lean on
 * that. What keeps it sound instead: the phone records the value it sent as the last one seen, so
 * its own write is never a change — and a report that has just gone out is given
 * [ResumeReport.WRITE_SETTLE_MS] to appear before a difference is believed, because a response
 * fetched a moment earlier still carries the old point.
 *
 * Doubles as the payload of a queued report, which is why it is the pair rather than a position:
 * what is sent and what is then recorded as seen have to be the same two values.
 */
data class RemoteResume(val positionMs: Long, val fullyPlayed: Boolean) {

    /** One preference key rather than two, so a half-written pair can never be read back. */
    fun encode(): String = "$positionMs:$fullyPlayed"

    companion object {
        fun decode(raw: String?): RemoteResume? {
            val parts = raw?.split(':') ?: return null
            if (parts.size != 2) return null
            val position = parts[0].toLongOrNull() ?: return null
            val played = parts[1].toBooleanStrictOrNull() ?: return null
            return RemoteResume(position, played)
        }
    }
}

/**
 * Whether to take Spotify's resume point for an episode, or keep the one this phone has.
 *
 * The problem this solves: start a podcast on the computer, pick it up on the phone. The naive
 * version — "remote wins when online" — throws away everything you listened to underground, and
 * "furthest position wins" ignores you scrubbing back on the desktop to re-hear something.
 *
 * The rule instead is **adopt only when the remote value moved**. The phone remembers the last
 * resume point it saw ([PodcastPreferences.lastSeenRemoteResume]); if Spotify is now reporting a
 * different one, some other client played this episode since we last looked, and that is newer
 * information than anything local by definition — including when it moved *backwards*. If it is
 * unchanged, the local position is the only one that can have moved, so it stands.
 *
 * Offline the rule never fires: with no response there is no remote value, and [Outcome.Keep] is
 * the answer.
 *
 * Extracted and tested because the two resume rules already here ([EpisodeResume], and the store's
 * own floor) have each caused a "podcasts always start from 0:00" report by disagreeing with a
 * caller.
 */
object EpisodeResumeSync {

    sealed interface Outcome {
        /** The local position stands. */
        data object Keep : Outcome

        /** Take Spotify's. [fullyPlayed] means mark it played rather than storing a position. */
        data class Adopt(val positionMs: Long, val fullyPlayed: Boolean) : Outcome
    }

    /**
     * @param remote what Spotify reports now, or null when it said nothing — offline, or a token
     *   minted before the `user-read-playback-position` scope, where the whole `resume_point`
     *   object is simply absent from the response.
     * @param lastSeen the last remote value this phone recorded for the episode — Spotify's answer
     *   the last time one was read, or the value this phone last successfully sent — or null if
     *   there has never been one.
     *
     * The caller has one duty this cannot express: while a report of its own is settling
     * ([ResumeReport.settling]) it must not call this *or* record what Spotify said. A response
     * fetched a moment before that write landed carries the point the write replaced, and recording
     * it would turn the replacement into a change worth adopting on the next load.
     */
    fun decide(remote: RemoteResume?, lastSeen: RemoteResume?): Outcome = when {
        remote == null -> Outcome.Keep

        // First sight. A zero that is not marked played says nothing — it is what Spotify reports
        // for every episode nobody has opened — and adopting it would wipe a local position on the
        // first list load after the scope is granted.
        lastSeen == null ->
            if (remote.fullyPlayed || remote.positionMs > EpisodeResume.RESUME_FLOOR_MS) {
                Outcome.Adopt(remote.positionMs, remote.fullyPlayed)
            } else {
                Outcome.Keep
            }

        remote == lastSeen -> Outcome.Keep

        else -> Outcome.Adopt(remote.positionMs, remote.fullyPlayed)
    }
}
