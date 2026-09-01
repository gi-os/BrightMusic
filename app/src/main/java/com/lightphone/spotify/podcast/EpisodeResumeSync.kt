package com.lightphone.spotify.podcast

/**
 * Spotify's own resume point for an episode, as it arrived from the Web API.
 *
 * Only ever written by Spotify's *other* clients — the desktop app, the phone app, the web player.
 * Nothing this app does moves it: the phone is not a Spotify Connect device (`rust/spotify-core`
 * builds librespot without `librespot-connect`, so no Spirc loop reports state), and the Web API has
 * no endpoint that sets a resume point. So a change in this value always means "you listened
 * somewhere else", which is what makes [EpisodeResumeSync.decide]'s rule work.
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
     * @param lastSeen the last remote value this phone recorded for the episode, or null if it has
     *   never seen one.
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
