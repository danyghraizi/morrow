package dev.morrow.domain

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * What the user's words say about timing.
 *
 * [start] is epoch millis, or null when the sentence carries no time at all
 * ("buy stamps"). [spans] records the character ranges that were consumed, so
 * the title can be cleaned by subtracting them.
 */
data class TimeSpec(
    val start: Long? = null,
    val hasDate: Boolean = false,
    val hasTime: Boolean = false,
    val durationMin: Int? = null,
    val spans: List<IntRange> = emptyList(),
)

/**
 * Deterministic, bilingual (EN/FR) time parsing. This is the part a 270M model
 * gets wrong, so it is plain code with tests.
 *
 * Matching happens on an accent-folded copy of the lowercased input. Folding is
 * one character for one character, so spans stay valid in the original string
 * and the title can be cleaned exactly.
 */
object When {

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
        // Full French day names only: "sam"/"mar"/"dim" collide with names and
        // with English month abbreviations ("Sam", "Mar").
        "lundi" to DayOfWeek.MONDAY,
        "mardi" to DayOfWeek.TUESDAY,
        "mercredi" to DayOfWeek.WEDNESDAY,
        "jeudi" to DayOfWeek.THURSDAY,
        "vendredi" to DayOfWeek.FRIDAY,
        "samedi" to DayOfWeek.SATURDAY,
        "dimanche" to DayOfWeek.SUNDAY,
    )

    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9, "october" to 10,
        "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
        "janvier" to 1, "janv" to 1, "fevrier" to 2, "fevr" to 2, "mars" to 3,
        "avril" to 4, "avr" to 4, "mai" to 5, "juin" to 6, "juillet" to 7, "juil" to 7,
        "aout" to 8, "septembre" to 9, "octobre" to 10, "novembre" to 11, "decembre" to 12,
    )

    private val WORD_HOURS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5,
        "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12,
    )

    private val WEEKDAY_RE = Regex(
        "\\b(?:(next|this|coming|on|le|ce|cet|la|du)\\s+)?(" + WEEKDAYS.keys.joinToString("|") +
            ")(?:\\s+(prochain|prochaine))?\\b"
    )
    private val DURATION_RE = Regex(
        "\\b(?:for|lasting|pendant|durant|pour)\\s+(?:(\\d+|an?|une?|one|half\\s+an?)\\s*)?(minutes?|mins?|heures?|hours?|hrs?)\\b"
    )
    private val OFFSET_RE = Regex(
        "\\b(?:in|dans)\\s+(\\d+|an?|une?|one)\\s*(minutes?|mins?|heures?|hours?|hrs?|jours?|days?|semaines?|weeks?)\\b"
    )
    private val DATE_NUMERIC_RE = Regex("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b")
    private val DATE_DAY_MONTH_RE = Regex(
        "\\b(?:(?:le|on)\\s+)?(\\d{1,2})(?:er|st|nd|rd|th)?\\s+(?:de\\s+|of\\s+)?(" + MONTHS.keys.joinToString("|") + ")\\b"
    )
    private val DATE_MONTH_DAY_RE = Regex(
        "\\b(" + MONTHS.keys.joinToString("|") + ")\\s+(\\d{1,2})(?:er|st|nd|rd|th)?\\b"
    )
    private val DATE_SLASH_RE = Regex("\\b(\\d{1,2})[/.](\\d{1,2})(?:[/.](\\d{2,4}))?\\b")
    /** French "à 15h", "vers 9h30", "avant 18h". */
    private val CLOCK_FR_RE = Regex(
        "\\b(?:a|vers|avant|d'ici|des|pour)\\s+(\\d{1,2})\\s*(?:h(?:eures?)?|heures?)\\s*(\\d{2})?\\b"
    )
    /** French glued "15h", "15h30", "9 h". */
    private val CLOCK_FR_GLUED_RE = Regex("\\b(\\d{1,2})\\s*h(?:eures?)?\\s*(\\d{2})?\\b")
    private val CLOCK_GLUED_RE = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)\\b")
    private val CLOCK_ANCHORED_RE = Regex(
        "\\b(?:at|@|by|around|before|until|till)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?\\b"
    )
    private val CLOCK_WORD_RE = Regex(
        "\\b(?:at|@|around|before|until|till|a|vers|avant)\\s+(" + WORD_HOURS.keys.joinToString("|") + ")\\b"
    )

    private val DAY_PARTS = listOf(
        Triple("morning", "matin", LocalTime.of(9, 0)),
        Triple("afternoon", "apres-midi", LocalTime.of(14, 0)),
        Triple("afternoon", "apres midi", LocalTime.of(14, 0)),
        Triple("evening", "soir", LocalTime.of(19, 0)),
    )
    private val NIGHT_WORDS = listOf("tonight", "ce soir", "night", "nuit")

    fun parse(raw: String, now: ZonedDateTime = ZonedDateTime.now()): TimeSpec {
        val text = fold(raw)
        // Consumed ranges are blanked out: "in 2 hours" must not be re-read as
        // the clock time 2h, and "at 9am" must be consumed as one span.
        val search = StringBuilder(text)
        val spans = mutableListOf<IntRange>()
        var day: LocalDate? = null
        var time: LocalTime? = null
        var duration: Int? = null
        var hasDate = false
        var hasTime = false
        var hint: LocalTime? = null

        fun record(range: IntRange) {
            if (range.first < 0 || range.last >= search.length) return
            spans += range
            for (index in range) search.setCharAt(index, ' ')
        }

        // 1. "in 20 minutes" / "dans 20 minutes": keeps an explicit clock below
        var anchor: ZonedDateTime? = null
        OFFSET_RE.find(search)?.let { m ->
            val n = wordToNumber(m.groupValues[1]) ?: 1
            val unit = m.groupValues[2]
            val target = when {
                unit.startsWith("min") -> now.plusMinutes(n.toLong())
                unit.startsWith("h") -> now.plusHours(n.toLong())
                unit.startsWith("d") || unit.startsWith("j") -> now.plusDays(n.toLong())
                else -> now.plusWeeks(n.toLong())
            }
            record(m.range)
            anchor = target
            hasTime = true
        }

        // 2. durations ("for an hour", "pendant 45 minutes")
        DURATION_RE.find(search)?.let { m ->
            val amount = m.groupValues[1].ifBlank { "1" }
            val n = if (amount.contains("half")) 0.5 else (wordToNumber(amount) ?: 1).toDouble()
            duration = (if (m.groupValues[2].startsWith("min")) n else n * 60).toInt().coerceAtLeast(5)
            record(m.range)
        }

        // 3. named days, English and French
        val named = if (anchor != null) emptyList() else listOf(
            "day after tomorrow" to 2, "apres-demain" to 2, "apres demain" to 2,
            "tomorrow" to 1, "demain" to 1,
            "today" to 0, "aujourd'hui" to 0, "aujourd hui" to 0, "aujourdhui" to 0,
        )
        for ((word, offset) in named) {
            val at = search.indexOf(word)
            if (at >= 0) {
                day = now.toLocalDate().plusDays(offset.toLong())
                hasDate = true
                record(at until at + word.length)
                break
            }
        }
        if (day == null && anchor == null) {
            for (word in listOf("next week", "la semaine prochaine", "semaine prochaine")) {
                val at = search.indexOf(word)
                if (at >= 0) {
                    day = now.toLocalDate().plusWeeks(1)
                    hasDate = true
                    record(at until at + word.length)
                    break
                }
            }
        }

        // 4. weekdays, with optional English prefix or French suffix
        if (day == null && anchor == null) {
            WEEKDAY_RE.find(search)?.let { m ->
                var date = now.toLocalDate().with(TemporalAdjusters.next(WEEKDAYS.getValue(m.groupValues[2])))
                if (m.groupValues[1] == "next" && date <= now.toLocalDate().plusDays(2)) date = date.plusWeeks(1)
                day = date
                hasDate = true
                record(m.range)
            }
        }

        // 5. explicit dates
        if (day == null && anchor == null) {
            DATE_NUMERIC_RE.find(search)?.let { m ->
                runCatching {
                    LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
                }.getOrNull()?.let { day = it; hasDate = true; record(m.range) }
            }
        }
        if (day == null && anchor == null) {
            DATE_DAY_MONTH_RE.find(search)?.let { m ->
                monthDay(m.groupValues[1].toIntOrNull(), MONTHS[m.groupValues[2]], now)?.let {
                    day = it; hasDate = true; record(m.range)
                }
            }
        }
        if (day == null && anchor == null) {
            DATE_MONTH_DAY_RE.find(search)?.let { m ->
                monthDay(m.groupValues[2].toIntOrNull(), MONTHS[m.groupValues[1]], now)?.let {
                    day = it; hasDate = true; record(m.range)
                }
            }
        }
        if (day == null && anchor == null) {
            DATE_SLASH_RE.find(search)?.let { m ->
                val first = m.groupValues[1].toInt()
                val second = m.groupValues[2].toInt()
                // 12/11 is day/month, the way most of the world writes it.
                val d = if (first > 12 && second <= 12) first else second
                val mo = if (first > 12 && second <= 12) second else first
                monthDay(d, mo, now)?.let { day = it; hasDate = true; record(m.range) }
            }
        }

        // 6. clock times
        val eveningHint = NIGHT_WORDS.any { search.contains(it) }
        val morningHint = search.contains("matin") || search.contains("morning")
        if (time == null) {
            CLOCK_FR_RE.find(search)?.let { m ->
                time = toLocalTime(m.groupValues[1].toInt(), m.groupValues[2], "", morningHint, eveningHint)
                hasTime = true
                record(m.range)
            }
        }
        if (time == null) {
            CLOCK_FR_GLUED_RE.find(search)?.let { m ->
                time = toLocalTime(m.groupValues[1].toInt(), m.groupValues[2], "", morningHint, eveningHint)
                hasTime = true
                record(m.range)
            }
        }
        if (time == null) {
            CLOCK_ANCHORED_RE.find(search)?.let { m ->
                time = toLocalTime(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3], morningHint, eveningHint)
                hasTime = true
                record(m.range)
            }
        }
        if (time == null) {
            CLOCK_GLUED_RE.find(search)?.let { m ->
                time = toLocalTime(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3], morningHint, eveningHint)
                hasTime = true
                record(m.range)
            }
        }
        if (time == null) {
            for ((word, value) in listOf("noon" to LocalTime.NOON, "midi" to LocalTime.NOON, "midnight" to LocalTime.MIDNIGHT, "minuit" to LocalTime.MIDNIGHT)) {
                val at = search.indexOf(word)
                if (at >= 0) {
                    time = value
                    hasTime = true
                    record(at until at + word.length)
                    break
                }
            }
        }
        if (time == null) {
            CLOCK_WORD_RE.find(search)?.let { m ->
                time = toLocalTime(WORD_HOURS.getValue(m.groupValues[1]), "", "", morningHint, eveningHint)
                hasTime = true
                record(m.range)
            }
        }

        // 7. "tomorrow morning" carries a time of day but no clock, so the row
        //    still reads as a day rather than a timestamp.
        if (time == null) {
            for ((en, fr, value) in DAY_PARTS) {
                val word = listOf(en, fr).firstOrNull { search.contains(it) } ?: continue
                val at = search.indexOf(word)
                hint = value
                record(at until at + word.length)
                break
            }
            if (hint == null) {
                val night = NIGHT_WORDS.firstOrNull { search.contains(it) }
                if (night != null) {
                    val at = search.indexOf(night)
                    hint = LocalTime.of(21, 0)
                    record(at until at + night.length)
                }
            }
        }

        val anchorValue = anchor
        if (anchorValue != null) {
            val clock = time ?: hint
            val result = if (clock != null) anchorValue.with(clock) else anchorValue
            return TimeSpec(
                start = result.toInstant().toEpochMilli(),
                hasDate = true,
                hasTime = time != null || hint != null,
                durationMin = duration,
                spans = spans,
            )
        }
        if (day == null && time == null && hint == null) {
            return TimeSpec(durationMin = duration, spans = spans)
        }

        val date = day ?: now.toLocalDate()
        val clock = time ?: hint ?: LocalTime.of(9, 0)
        var result = ZonedDateTime.of(date, clock, now.zone)
        if (!hasDate && result.isBefore(now.plusMinutes(1))) {
            result = result.plusDays(1)
        }
        return TimeSpec(
            start = result.toInstant().toEpochMilli(),
            hasDate = hasDate,
            hasTime = hasTime,
            durationMin = duration,
            spans = spans,
        )
    }

    /** Removes parsed time words from a sentence and tidies it into a title. */
    fun cleanTitle(raw: String, spec: TimeSpec): String {
        // Spans can nest ("every monday" contains "monday"), so merge before
        // deleting, otherwise the second delete eats the wrong characters.
        val merged = mutableListOf<IntRange>()
        for (span in spec.spans.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && span.first <= last.last + 1) {
                if (span.last > last.last) merged[merged.lastIndex] = last.first..span.last
            } else {
                merged += span
            }
        }
        val chars = StringBuilder(raw)
        for (span in merged.sortedByDescending { it.first }) {
            if (span.first >= 0 && span.last < chars.length) chars.delete(span.first, span.last + 1)
        }
        return tidy(chars.toString())
    }

    fun tidy(raw: String): String {
        var s = raw.trim()
        var changed = true
        while (changed) {
            changed = false
            for (prefix in PREFIXES) {
                if (s.length < prefix.length) continue
                if (!s.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) continue
                if (s.length > prefix.length && s[prefix.length].isLetterOrDigit()) continue
                s = s.substring(prefix.length).trimStart(' ', ',', ';', ':', '-', '–', '\u2014', '\'')
                changed = true
            }
        }
        s = s.replace(Regex("\\s+"), " ").trim()
        // Compare accent-folded so "à", "le", "de" are stripped as reliably as "at".
        var folded = fold(s)
        for (word in TRAILING_WORDS) {
            val suffix = " $word"
            if (folded.endsWith(suffix)) {
                s = s.dropLast(suffix.length).trim()
                folded = fold(s)
            }
        }
        s = s.trim(' ', ',', ';', ':', '.', '-', '–', '\u2014', '\'')
        if (s.isEmpty()) return s
        return s.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private val TRAILING_WORDS = listOf(
        "at", "on", "by", "for", "and", "to", "in", "of",
        "a", "au", "aux", "le", "la", "les", "du", "de", "des", "et", "pour", "dans", "sur",
    )

    private val PREFIXES = listOf(
        "remind me to", "remind me", "remind", "i have to", "i need to", "i must", "i gotta",
        "i got to", "dont forget to", "don't forget to", "don't forget", "please", "add a task to",
        "add a task", "add an event", "add event", "add task", "add", "put", "schedule",
        "remember to", "remember that", "remember", "note that", "note", "new task", "new event", "task:",
        "event:", "todo:", "to do:", "to-do:",
        "rappelle-moi de", "rappelle-moi", "rappelez-moi de", "rappelez-moi", "rappelle moi de",
        "rappelle moi", "pense a", "n'oublie pas de", "noublie pas de", "il faut que je",
        "je dois", "j'ai", "jai", "ajoute", "ajouter", "note que", "mets", "programme", "planifie",
    )

    /** Accent- and case-insensitive copy with a one-to-one index mapping. */
    internal fun fold(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw) {
            val lower = ch.lowercaseChar()
            if (lower.code < 128) {
                out.append(lower)
                continue
            }
            val stripped = Normalizer.normalize(lower.toString(), Normalizer.Form.NFD)
                .filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
            out.append(if (stripped.length == 1) stripped[0] else lower)
        }
        return out.toString()
    }

    private fun monthDay(day: Int?, month: Int?, now: ZonedDateTime): LocalDate? {
        if (day == null || month == null || day !in 1..31 || month !in 1..12) return null
        val thisYear = runCatching { LocalDate.of(now.year, month, day) }.getOrNull() ?: return null
        return if (thisYear.isBefore(now.toLocalDate())) thisYear.plusYears(1) else thisYear
    }

    private fun toLocalTime(hour: Int, minutes: String, meridiem: String, morning: Boolean, evening: Boolean): LocalTime {
        val minute = minutes.toIntOrNull() ?: 0
        var h = hour
        when {
            meridiem.startsWith("p") -> if (h < 12) h += 12
            meridiem.startsWith("a") -> if (h == 12) h = 0
            h > 23 -> h = 23
            // Bare hours: "at 3" almost always means afternoon, "at 9" morning.
            morning && h < 12 -> h = if (h == 12) 0 else h
            evening && h in 1..11 -> h += 12
            h in 1..7 -> h += 12
        }
        return LocalTime.of(h.coerceIn(0, 23), minute.coerceIn(0, 59))
    }

    private fun wordToNumber(value: String): Int? {
        val v = value.trim()
        if (v.isEmpty()) return null
        v.toIntOrNull()?.let { return it }
        if (v == "a" || v == "an" || v == "un" || v == "une" || v == "one") return 1
        return WORD_HOURS[v]
    }
}
