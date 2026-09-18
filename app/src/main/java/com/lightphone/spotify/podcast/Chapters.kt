package com.lightphone.spotify.podcast

/**
 * One chapter of an episode: a title and the stretch of the episode it covers.
 *
 * [endMs] is exclusive, and is always filled in — from the next chapter's start, or the episode's
 * length — because a chapter with no end cannot be drawn as a band on a scrub bar.
 */
data class Chapter(val title: String, val startMs: Long, val endMs: Long)

/**
 * Chapters read out of an episode's show notes.
 *
 * The fallback for the chapters Spotify itself holds (`rust/spotify-core/src/chapters.rs`). It
 * exists because the two sources barely overlap: Spotify's own are generated from a transcript and
 * only for English shows it has got to, while a publisher who has written "00:00 Intro" into the
 * description has said exactly what they meant and said it everywhere their feed goes. Costs no
 * network call either — the description is already on the episode this app has loaded.
 *
 * The rules are Spotify's own, from its creator documentation, and they are followed to the letter
 * so that what shows here is what shows there:
 *
 * - `MM:SS` or `HH:MM:SS`, bare or in brackets, at the start of its own line.
 * - The first must be zero.
 * - At least three of them, in order, at least thirty seconds apart.
 * - A leading index on the title is dropped: `1 - Introduction` is `Introduction`.
 *
 * All or nothing. A description that breaks a rule yields no chapters rather than a partial list,
 * because the usual cause of a broken rule is that those numbers were never a chapter list — a
 * mention of a time in the text, a sponsor read, a phone number. Marks in the wrong places on the
 * bar are worse than no marks.
 */
object DescriptionChapters {

    const val MIN_CHAPTERS = 3
    const val MIN_GAP_MS = 30_000L

    private val LINE = Regex(
        """^\s*[\[(]?\s*(\d{1,3}):([0-5]\d)(?::([0-5]\d))?\s*[)\]]?\s*[-–—:|.]?\s*(.+?)\s*$""",
    )

    /** A number, or a number and a separator, in front of the real title: "1 - ", "2.", "03)". */
    private val LEADING_INDEX = Regex("""^\d{1,3}\s*[-–—.):]\s*""")

    fun parse(description: String?, durationMs: Long): List<Chapter> {
        if (description.isNullOrBlank()) return emptyList()

        val found = mutableListOf<Pair<Long, String>>()
        for (rawLine in description.lineSequence()) {
            val match = LINE.find(rawLine) ?: continue
            val (first, second, third, rest) = match.destructured
            // Two groups is mm:ss, three is hh:mm:ss. Written this way round because "1:02:03" and
            // "62:03" are the same episode position and both are used in the wild.
            val positionMs = if (third.isEmpty()) {
                (first.toLong() * 60 + second.toLong()) * 1000L
            } else {
                (first.toLong() * 3600 + second.toLong() * 60 + third.toLong()) * 1000L
            }
            val title = LEADING_INDEX.replace(rest, "").trim()
            if (title.isEmpty()) continue
            found += positionMs to title
        }

        if (found.size < MIN_CHAPTERS) return emptyList()
        if (found.first().first != 0L) return emptyList()
        for (index in 1 until found.size) {
            if (found[index].first - found[index - 1].first < MIN_GAP_MS) return emptyList()
        }
        // A timestamp past the end of the episode is proof these are not chapters — a duration in
        // the text, or a list copied from a different episode.
        if (durationMs > 0L && found.last().first >= durationMs) return emptyList()

        return found.mapIndexed { index, (startMs, title) ->
            val end = found.getOrNull(index + 1)?.first
                ?: durationMs.takeIf { it > startMs }
                ?: startMs
            Chapter(title = title, startMs = startMs, endMs = end)
        }
    }
}

/** The chapter playing at [positionMs], or null when there are none or it sits before the first. */
fun List<Chapter>.chapterAt(positionMs: Long): Chapter? =
    lastOrNull { positionMs >= it.startMs && (positionMs < it.endMs || it.endMs <= it.startMs) }
