package com.lightphone.spotify.podcast

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Turns Spotify's `release_date` into something a person reads rather than parses.
 *
 * A podcast listener's first question about an episode is "how new is this", and
 * `2026-07-12` answers it only after mental arithmetic. Inside a week the answer is
 * relative ("Yesterday", "3 days ago"); past that a date is more useful than a count, and
 * the year is dropped when it is this one because it is noise on a 3.92" screen.
 *
 * Spotify's `release_date_precision` is honoured: some feeds only give a month, a few only
 * a year, and inventing a day for those would be a lie.
 */
object ReleaseDate {

    private const val RELATIVE_DAYS = 6L

    fun human(raw: String?, precision: String? = "day", today: LocalDate = LocalDate.now()): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        return when (precision?.lowercase(Locale.US)) {
            "year" -> year(text)
            "month" -> month(text)
            else -> day(text, today) ?: month(text) ?: year(text) ?: text
        }
    }

    private fun year(text: String): String? =
        text.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }

    private fun month(text: String): String? {
        val ym = runCatching { YearMonth.parse(text.take(7)) }.getOrNull() ?: return null
        return "${ym.month.shortName()} ${ym.year}"
    }

    private fun day(text: String, today: LocalDate): String? {
        val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return null
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days in 2L..RELATIVE_DAYS -> "$days days ago"
            // Scheduled episodes and anything else in the future read as a plain date.
            date.year == today.year -> "${date.dayOfMonth} ${date.month.shortName()}"
            else -> "${date.dayOfMonth} ${date.month.shortName()} ${date.year}"
        }
    }

    private fun java.time.Month.shortName(): String =
        getDisplayName(TextStyle.SHORT, Locale.getDefault())
}
