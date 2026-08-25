package com.lightphone.spotify.podcast

/**
 * What you have and have not heard, as a mark rather than a sentence.
 *
 * The subtitle on an episode row already says "Played" or "22 min left", and it is the right place
 * for both — but a list you are working through is read by scanning, and a word in the second line
 * of a row is not something you scan. A dot is. So the marks here answer one question only: **is
 * there anything on this row I have not heard at all?**
 *
 * Three states rather than two, and only one of them draws:
 *
 *  - [EpisodeMark.Unheard] — never started, never finished. This is the dot.
 *  - [EpisodeMark.Started] — there is a resume position. No dot: the row already says how much is
 *    left, and a dot beside it would be a second, vaguer answer to the same question.
 *  - [EpisodeMark.Heard] — played to the end, or marked played by hand.
 *
 * Something the phone cannot play is never [EpisodeMark.Unheard]. The row is greyed out because
 * tapping it does nothing, and a dot inviting you to hear something unhearable is worse than no
 * mark at all.
 */
enum class EpisodeMark { Unheard, Started, Heard }

object Unheard {

    /** The mark for one episode. Pure: everything it needs is already in the caller's hands. */
    fun markFor(played: Boolean, resumeMs: Long, playable: Boolean): EpisodeMark = when {
        played -> EpisodeMark.Heard
        !playable -> EpisodeMark.Heard
        resumeMs > 0L -> EpisodeMark.Started
        else -> EpisodeMark.Unheard
    }

    /** True when this row should carry a dot. */
    fun dotted(played: Boolean, resumeMs: Long, playable: Boolean): Boolean =
        markFor(played, resumeMs, playable) == EpisodeMark.Unheard

    /**
     * Which followed shows have something unheard waiting, from the newest episode of each.
     *
     * **The newest episode only, not a count.** A count would mean holding every show's whole feed
     * on the phone and keeping it current, and the question a shows list is being asked is "is there
     * anything new here", which the newest episode answers on its own. It is also the only episode
     * the daily probe fetches — see [UnheardProbe] — so a count would be a number the app cannot
     * actually stand behind.
     *
     * A show whose newest episode is not recorded yet is absent rather than dotted: no dot means
     * "nothing known to be waiting", and inventing one for a show nobody has looked at would put a
     * dot on every row on first launch.
     */
    fun showsWithUnheard(
        newestByShow: Map<String, String>,
        played: Set<String>,
        resumeMsOf: (String) -> Long,
    ): Set<String> = newestByShow
        .filterValues { uri ->
            uri.isNotBlank() && dotted(played = uri in played, resumeMs = resumeMsOf(uri), playable = true)
        }
        .keys
}
