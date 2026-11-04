package com.shinbhairava.tdflew.engine

import com.shinbhairava.tdflew.drive.DriveItem
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

enum class GalleryPreset(val label: String) {
    ALL("All dates"),
    TODAY("Today"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    THIS_YEAR("This year"),
}

/**
 * Parsed search query for the gallery.
 *
 * Supports free text plus compact date tags:
 *   date:2026-11-04   on:2026-11   from:2026-01-01   to:2026-12-31
 * A bare ISO year, year-month, or date is also treated as a date tag.
 */
data class GalleryQuery(
    val text: String,
    val dateRange: DateRange?,
) {
    companion object {
        val Empty = GalleryQuery(text = "", dateRange = null)

        fun parse(raw: String): GalleryQuery {
            val textTokens = mutableListOf<String>()
            var start: LocalDate? = null
            var endExclusive: LocalDate? = null

            raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { token ->
                val (name, value) = splitTag(token)
                when (name) {
                    "from", "after" -> {
                        val parsed = parseDatePrefix(value)
                        if (parsed != null) start = laterOf(start, parsed.first)
                        else textTokens += token
                    }

                    "to", "before" -> {
                        val parsed = parseDatePrefix(value)
                        if (parsed != null) endExclusive = earlierOf(endExclusive, parsed.second)
                        else textTokens += token
                    }

                    "date", "on" -> {
                        val parsed = parseDatePrefix(value)
                        if (parsed != null) {
                            start = laterOf(start, parsed.first)
                            endExclusive = earlierOf(endExclusive, parsed.second)
                        } else {
                            textTokens += token
                        }
                    }

                    else -> {
                        val parsed = parseDatePrefix(token.removePrefix("@"))
                        if (parsed != null && looksLikeDateToken(token)) {
                            start = laterOf(start, parsed.first)
                            endExclusive = earlierOf(endExclusive, parsed.second)
                        } else {
                            textTokens += token
                        }
                    }
                }
            }

            val range = if (start == null && endExclusive == null) {
                null
            } else {
                DateRange(
                    start = start ?: LocalDate.MIN,
                    endExclusive = endExclusive ?: LocalDate.MAX,
                )
            }
            return GalleryQuery(text = textTokens.joinToString(" "), dateRange = range)
        }

        private fun splitTag(token: String): Pair<String, String> {
            val index = token.indexOf(':')
            if (index <= 0 || index == token.lastIndex) return "" to token
            return token.substring(0, index).lowercase() to token.substring(index + 1)
        }

        private fun looksLikeDateToken(token: String): Boolean =
            Regex("^\\d{4}(-\\d{2})?(-\\d{2})?$").matches(token)

        /**
         * Returns an inclusive start date and an exclusive end date for an ISO
         * day, month, or year. A full `from:` day means that exact day.
         */
        private fun parseDatePrefix(value: String): Pair<LocalDate, LocalDate>? {
            return try {
                when {
                    Regex("^\\d{4}$").matches(value) -> {
                        val year = value.toInt()
                        LocalDate.of(year, 1, 1) to LocalDate.of(year + 1, 1, 1)
                    }

                    Regex("^\\d{4}-\\d{2}$").matches(value) -> {
                        val month = YearMonth.parse(value)
                        month.atDay(1) to month.plusMonths(1).atDay(1)
                    }

                    Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value) -> {
                        val day = LocalDate.parse(value)
                        day to day.plusDays(1)
                    }

                    else -> null
                }
            } catch (_: DateTimeParseException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }

        private fun laterOf(first: LocalDate?, second: LocalDate): LocalDate =
            if (first == null || second.isAfter(first)) second else first

        private fun earlierOf(first: LocalDate?, second: LocalDate): LocalDate =
            if (first == null || second.isBefore(first)) second else first
    }
}

data class DateRange(
    val start: LocalDate,
    val endExclusive: LocalDate,
) {
    fun contains(date: LocalDate): Boolean =
        !date.isBefore(start) && date.isBefore(endExclusive)

    fun intersect(other: DateRange): DateRange? {
        val laterStart = if (start.isAfter(other.start)) start else other.start
        val earlierEnd = if (endExclusive.isBefore(other.endExclusive)) endExclusive else other.endExclusive
        return if (laterStart.isBefore(earlierEnd)) DateRange(laterStart, earlierEnd) else null
    }
}

fun galleryPresetRange(preset: GalleryPreset, today: LocalDate = LocalDate.now()): DateRange? {
    return when (preset) {
        GalleryPreset.ALL -> null
        GalleryPreset.TODAY -> DateRange(today, today.plusDays(1))
        GalleryPreset.THIS_WEEK -> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            DateRange(monday, monday.plusWeeks(1))
        }

        GalleryPreset.THIS_MONTH -> {
            val first = today.withDayOfMonth(1)
            DateRange(first, first.plusMonths(1))
        }

        GalleryPreset.THIS_YEAR -> {
            val first = today.withDayOfYear(1)
            DateRange(first, first.plusYears(1))
        }
    }
}

fun filterGallery(
    items: List<DriveItem>,
    query: GalleryQuery,
    preset: GalleryPreset,
    today: LocalDate = LocalDate.now(),
): List<DriveItem> {
    val queryRange = query.dateRange
    val presetRange = galleryPresetRange(preset, today)
    val range = when {
        queryRange == null -> presetRange
        presetRange == null -> queryRange
        else -> queryRange.intersect(presetRange)
    }
    // A null range means either "no filter" or "the query and preset do not
    // overlap". Only the former should match every item.
    if (range == null && queryRange != null && presetRange != null) return emptyList()
    val terms = query.text
        .lowercase()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

    return items.asSequence()
        .filter { item ->
            if (range == null) return@filter true
            val date = java.time.Instant.ofEpochMilli(item.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate()
            range.contains(date)
        }
        .filter { item ->
            if (terms.isEmpty()) return@filter true
            val haystack = "${item.name} ${item.path}".lowercase()
            terms.all { haystack.contains(it) }
        }
        .sortedByDescending { it.timestamp }
        .toList()
}
