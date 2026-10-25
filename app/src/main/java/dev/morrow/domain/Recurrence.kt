package dev.morrow.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Repetition rules, stored as the iCalendar string the calendar provider already
 * understands ("FREQ=WEEKLY;BYDAY=MO").
 *
 * The app materialises occurrences instead of expanding a rule at render time:
 * every occurrence is a real row, so alarms, completion and deletion keep
 * working with no special cases. [nextAfter] and [occurrences] decide what to
 * create; [topUp] keeps a rolling horizon on app start.
 */
data class Recurrence(
    val freq: Freq,
    val days: Set<DayOfWeek> = emptySet(),
    val interval: Int = 1,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY }

    val isWeekdays: Boolean get() = freq == Freq.WEEKLY && days.size == 5 && days == WEEKDAYS

    fun rrule(): String = when (freq) {
        Freq.DAILY -> if (interval == 1) "FREQ=DAILY" else "FREQ=DAILY;INTERVAL=$interval"
        Freq.MONTHLY -> if (interval == 1) "FREQ=MONTHLY" else "FREQ=MONTHLY;INTERVAL=$interval"
        Freq.WEEKLY -> buildString {
            append("FREQ=WEEKLY")
            if (days.isNotEmpty()) append(";BYDAY=").append(days.sortedBy { it.value }.joinToString(",") { code(it) })
            if (interval != 1) append(";INTERVAL=").append(interval)
        }
    }

    /**
     * The next occurrence strictly after [after]. For weekly rules with explicit
     * days the interval is ignored (only "every N weeks" carries one).
     */
    fun nextAfter(after: ZonedDateTime): ZonedDateTime = when (freq) {
        Freq.DAILY -> after.plusDays(interval.toLong())
        Freq.MONTHLY -> after.plusMonths(interval.toLong())
        Freq.WEEKLY -> {
            if (days.isEmpty()) {
                after.plusWeeks(interval.toLong())
            } else {
                var candidate = after.toLocalDate().plusDays(1)
                var guard = 0
                while (guard++ < 400) {
                    if (candidate.dayOfWeek in days) {
                        return ZonedDateTime.of(candidate, after.toLocalTime(), after.zone)
                    }
                    candidate = candidate.plusDays(1)
                }
                after.plusWeeks(1)
            }
        }
    }

    /**
     * `count` occurrences starting at [first], inclusive.
     */
    fun occurrences(first: ZonedDateTime, count: Int): List<ZonedDateTime> {
        val out = ArrayList<ZonedDateTime>(count)
        out += first
        while (out.size < count) out += nextAfter(out.last())
        return out
    }

    companion object {
        val WEEKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )

        private val DAY_WORDS = mapOf(
            "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
            "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
            "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
            "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
            "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
            "lundi" to DayOfWeek.MONDAY,
            "mardi" to DayOfWeek.TUESDAY,
            "mercredi" to DayOfWeek.WEDNESDAY,
            "jeudi" to DayOfWeek.THURSDAY,
            "vendredi" to DayOfWeek.FRIDAY,
            "samedi" to DayOfWeek.SATURDAY,
            "dimanche" to DayOfWeek.SUNDAY,
            "mondays" to DayOfWeek.MONDAY, "tuesdays" to DayOfWeek.TUESDAY,
            "wednesdays" to DayOfWeek.WEDNESDAY, "thursdays" to DayOfWeek.THURSDAY,
            "fridays" to DayOfWeek.FRIDAY, "saturdays" to DayOfWeek.SATURDAY,
            "sundays" to DayOfWeek.SUNDAY,
            "lundis" to DayOfWeek.MONDAY, "mardis" to DayOfWeek.TUESDAY,
            "mercredis" to DayOfWeek.WEDNESDAY, "jeudis" to DayOfWeek.THURSDAY,
            "vendredis" to DayOfWeek.FRIDAY, "samedis" to DayOfWeek.SATURDAY,
            "dimanches" to DayOfWeek.SUNDAY,
        )

        private val EVERY_RE = Regex(
            "\\b(?:every|each|tous les|toutes les|chaque|tout les)\\s+(?:(\\d+)\\s+)?" +
                "(day|days|weekday|weekdays|week|weeks|month|months|jour|jours|semaine|semaines|mois|" +
                DAY_WORDS.keys.joinToString("|") + ")\\b"
        )
        private val WEEKDAYS_RE = Regex("\\b(?:every weekday|weekdays|en semaine|jours ouvrables)\\b")
        private val DAILY_RE = Regex("\\b(?:every day|daily|tous les jours|chaque jour|quotidien)\\b")

        /**
         * Reads a repetition phrase out of a sentence.
         * @return the rule and the character span to remove from the title.
         */
        fun parse(raw: String): Pair<Recurrence, IntRange>? {
            val text = When.fold(raw)
            WEEKDAYS_RE.find(text)?.let { m ->
                return Recurrence(Freq.WEEKLY, WEEKDAYS) to m.range
            }
            EVERY_RE.find(text)?.let { m ->
                val interval = m.groupValues[1].toIntOrNull() ?: 1
                val word = m.groupValues[2]
                val day = DAY_WORDS[word]
                if (day != null) {
                    return Recurrence(Freq.WEEKLY, setOf(day), interval) to m.range
                }
                return when {
                    word.startsWith("jour") || word.startsWith("day") -> Recurrence(Freq.DAILY, interval = interval) to m.range
                    word.startsWith("semaine") || word.startsWith("week") || word == "mois" ->
                        Recurrence(Freq.WEEKLY, interval = interval) to m.range
                    else -> Recurrence(Freq.MONTHLY, interval = interval) to m.range
                }
            }
            DAILY_RE.find(text)?.let { m ->
                return Recurrence(Freq.DAILY) to m.range
            }
            return null
        }

        fun fromRrule(rrule: String): Recurrence? {
            val upper = rrule.uppercase(Locale.US)
            if (!upper.startsWith("FREQ=")) return null
            val freq = when (upper.substringAfter("FREQ=").substringBefore(';')) {
                "DAILY" -> Freq.DAILY
                "WEEKLY" -> Freq.WEEKLY
                "MONTHLY" -> Freq.MONTHLY
                else -> return null
            }
            val interval = Regex("INTERVAL=(\\d+)").find(upper)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val days = Regex("BYDAY=([A-Z,]+)").find(upper)?.groupValues?.get(1)
                ?.split(',')?.mapNotNull { code -> DAY_BY_CODE[code] }?.toSet() ?: emptySet()
            return Recurrence(freq, days, interval.coerceAtLeast(1))
        }

        /**
         * Adds the occurrences needed so a series always covers the next
         * [horizonDays] days (bounded, so a daily rule cannot create thousands).
         */
        fun missingOccurrences(
            series: List<dev.morrow.data.Item>,
            now: ZonedDateTime,
            horizonDays: Long = 21,
            maxRows: Int = 14,
        ): List<dev.morrow.data.Item> {
            if (series.isEmpty()) return emptyList()
            val last = series.maxByOrNull { it.start } ?: return emptyList()
            val rule = fromRrule(last.rrule ?: return emptyList()) ?: return emptyList()
            if (series.size >= maxRows) return emptyList()
            val horizon = now.plusDays(horizonDays).toInstant().toEpochMilli()
            if (last.start >= horizon) return emptyList()

            val template = last
            val existingStarts = series.map { it.start }.toSet()
            val created = mutableListOf<dev.morrow.data.Item>()
            var cursor = java.time.Instant.ofEpochMilli(last.start).atZone(now.zone)
            val duration = if (last.end != null) last.end - last.start else 0L
            while (cursor.toInstant().toEpochMilli() < horizon && series.size + created.size < maxRows) {
                cursor = rule.nextAfter(cursor)
                if (cursor.toInstant().toEpochMilli() in existingStarts) continue
                created += template.copy(
                    id = 0L,
                    start = cursor.toInstant().toEpochMilli(),
                    end = if (duration > 0) cursor.toInstant().toEpochMilli() + duration else null,
                    done = false,
                    doneAt = null,
                    reminderAt = if (template.isEvent) cursor.toInstant().toEpochMilli() - (template.start - (template.reminderAt ?: template.start)) else cursor.toInstant().toEpochMilli(),
                    calendarEventId = template.calendarEventId,
                    createdAt = now.toInstant().toEpochMilli(),
                )
            }
            return created
        }

        private fun code(day: DayOfWeek): String = when (day) {
            DayOfWeek.MONDAY -> "MO"
            DayOfWeek.TUESDAY -> "TU"
            DayOfWeek.WEDNESDAY -> "WE"
            DayOfWeek.THURSDAY -> "TH"
            DayOfWeek.FRIDAY -> "FR"
            DayOfWeek.SATURDAY -> "SA"
            DayOfWeek.SUNDAY -> "SU"
        }

        private val DAY_BY_CODE = DayOfWeek.entries.associateBy { code(it) }
    }
}
