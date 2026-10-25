package dev.morrow.domain

import dev.morrow.data.Item
import dev.morrow.data.Kind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** A short, actionable observation about the day. Computed locally, always. */
data class Insight(val id: String, val text: String, val action: InsightAction)

sealed interface InsightAction {
    data class MoveToToday(val ids: List<Long>) : InsightAction
    data class Schedule(val id: Long, val at: Long) : InsightAction
    data class MoveToTomorrow(val id: Long) : InsightAction
    data object None : InsightAction
}

/**
 * Cheap heuristics that make the app feel like it is paying attention without
 * running anything in the background: overdue work, a real gap in the day, an
 * empty day, and a day that is stacked while tomorrow is clear.
 */
object Insights {

    fun forDay(
        items: List<Item>,
        now: ZonedDateTime = ZonedDateTime.now(),
        copy: Copy = Copy.current(),
    ): List<Insight> {
        val open = items.filter { !it.done }
        val out = mutableListOf<Insight>()
        val nowMs = now.toInstant().toEpochMilli()

        // Only rows Morrow owns can be rescheduled; a past calendar entry is
        // history, not a loose end.
        val overdue = open.filter { it.id > 0 && it.start in 1 until nowMs }
        if (overdue.isNotEmpty()) {
            val names = overdue.take(2).joinToString(", ") { it.title }
            out += Insight(
                id = "overdue",
                text = copy.overdueInsight(overdue.size, names, (overdue.size - 2).coerceAtLeast(0)),
                action = InsightAction.MoveToToday(overdue.map { it.id }),
            )
        }

        val dayStart = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        val dayEnd = ZonedDateTime.of(now.toLocalDate(), LocalTime.of(21, 0), now.zone).toInstant().toEpochMilli()
        val tomorrowEnd = now.toLocalDate().plusDays(2).atStartOfDay(now.zone).toInstant().toEpochMilli()

        val todayOpen = open.filter { it.start in dayStart..dayEnd }
        val tomorrowOpen = open.filter { it.start in (dayEnd + 1)..<tomorrowEnd }

        val busy = open.filter { it.isEvent && it.start in nowMs..dayEnd }.sortedBy { it.start }
        val candidate = open.filter { !it.isEvent && it.isAnytime }.minByOrNull { it.createdAt }
            ?: open.filter { !it.isEvent }.minByOrNull { it.start }

        if (candidate != null && todayOpen.size < 4) {
            val gapStart = nextGapStart(busy, now, ZonedDateTime.ofInstant(Instant.ofEpochMilli(dayEnd), now.zone))
            if (gapStart != null) {
                val from = Instant.ofEpochMilli(gapStart).atZone(now.zone)
                val to = from.plusHours(1)
                out += Insight(
                    id = "gap",
                    text = copy.gapInsight(Fmt.time(gapStart, now.zone), Fmt.time(to.toInstant().toEpochMilli(), now.zone), candidate.title),
                    action = InsightAction.Schedule(candidate.id, gapStart),
                )
            }
        }

        // A stacked day with an empty tomorrow is the classic moment to move one thing.
        if (todayOpen.size >= 4 && tomorrowOpen.isEmpty()) {
            val movable = todayOpen.filter { it.id > 0 && !it.isEvent }.maxByOrNull { it.start }
            if (movable != null) {
                out += Insight(
                    id = "tomorrow",
                    text = copy.tomorrowFreeInsight(movable.title),
                    action = InsightAction.MoveToTomorrow(movable.id),
                )
            }
        }

        if (todayOpen.isEmpty() && out.isEmpty()) {
            out += Insight(id = "empty", text = copy.emptyDayInsight(), action = InsightAction.None)
        }
        return out.take(3)
    }

    /** First moment from now where there is at least an hour before the next event. */
    private fun nextGapStart(busy: List<Item>, now: ZonedDateTime, dayEnd: ZonedDateTime): Long? {
        var cursor = now.plusMinutes(10)
        for (event in busy) {
            val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(event.start), now.zone)
            val end = ZonedDateTime.ofInstant(Instant.ofEpochMilli(event.end ?: (event.start + 3_600_000L)), now.zone)
            if (Duration.between(cursor, start).toMinutes() >= 60) return cursor.toInstant().toEpochMilli()
            if (end.isAfter(cursor)) cursor = end
        }
        if (Duration.between(cursor, dayEnd).toMinutes() >= 60) return cursor.toInstant().toEpochMilli()
        return null
    }

    fun agendaText(
        items: List<Item>,
        now: ZonedDateTime = ZonedDateTime.now(),
        copy: Copy = Copy.current(),
        day: LocalDate = now.toLocalDate(),
    ): String {
        val zone = now.zone
        val startOfDay = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val isToday = day == now.toLocalDate()
        val open = items.filter { !it.done }
        val today = open.filter { it.start in startOfDay..<endOfDay }.sortedBy { it.start }
        val anytime = open.filter { it.isAnytime }
        val overdueCount = open.count { it.id > 0 && it.start in 1 until now.toInstant().toEpochMilli() }

        if (today.isEmpty() && anytime.isEmpty() && overdueCount == 0) return copy.agendaEmpty()

        val label = if (isToday) null else copy.dayLabel(day, now)
        val list = today.joinToString(copy.separator()) { item -> "${Fmt.time(item.start, zone)} ${item.title}" }
        val sb = StringBuilder()
        if (today.isEmpty()) {
            sb.append(if (label == null) copy.agendaNothingToday() else copy.agendaNothingOnDay(label))
        } else {
            sb.append(if (label == null) copy.agendaToday(today.size, list) else copy.agendaOnDay(label, today.size, list))
        }
        if (overdueCount > 0) sb.append(copy.agendaSlipped(overdueCount))
        if (anytime.isNotEmpty()) {
            sb.append(copy.agendaAnytime(anytime.take(3).joinToString(copy.separator()) { it.title }))
        }
        val firstEvent = if (isToday) today.firstOrNull { it.kind == Kind.EVENT } else null
        if (firstEvent != null) {
            val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(firstEvent.start), zone)
            val minutes = Duration.between(now, start).toMinutes()
            if (minutes in 1..180) sb.append(copy.agendaNextUp(minutes, firstEvent.title))
        }
        return sb.toString().trim()
    }

    fun suggestText(
        items: List<Item>,
        now: ZonedDateTime = ZonedDateTime.now(),
        copy: Copy = Copy.current(),
    ): String {
        val local = forDay(items, now, copy).filter { it.action != InsightAction.None }
        if (local.isEmpty()) return copy.nothingToSuggest()
        return local.joinToString("\n") { it.text }
    }

    /** Human label for a repetition rule, e.g. "every Monday". */
    fun repeatLabel(rrule: String?, copy: Copy = Copy.current()): String? =
        rrule?.let { copy.repeatLabel(it) }
}
