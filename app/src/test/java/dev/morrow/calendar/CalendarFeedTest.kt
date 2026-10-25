package dev.morrow.calendar

import dev.morrow.data.Item
import dev.morrow.data.Kind
import dev.morrow.domain.InsightAction
import dev.morrow.domain.Insights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Rows read from the system calendar are merged into Today but never owned by
 * Morrow. These tests pin the merge rules and the effect on suggestions.
 */
class CalendarFeedTest {

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 25, 13, 0, 0, 0, ZoneId.of("UTC"))
    private val ms = now.toInstant().toEpochMilli()

    private fun event(eventId: Long, title: String, fromMin: Long, toMin: Long, allDay: Boolean = false) =
        ExternalEvent(
            eventId = eventId,
            title = title,
            start = ms + fromMin * 60_000,
            end = ms + toMin * 60_000,
            allDay = allDay,
            calendarName = "you@gmail.com",
        )

    private val taxes = Item.task("Finish taxes", 0L, null, "rules", ms)

    @Test
    fun `external events are appended with read-only ids`() {
        val merged = CalendarFeed.merge(listOf(taxes), listOf(event(77, "Standup", 60, 90)))
        assertEquals(2, merged.size)
        val mirror = merged.last()
        assertEquals("Standup", mirror.title)
        assertEquals(Kind.EVENT, mirror.kind)
        assertTrue(mirror.id < 0)
        assertEquals(77L, mirror.calendarEventId)
        assertFalse(mirror.done)
    }

    @Test
    fun `events Morrow already wrote are not duplicated`() {
        val linked = taxes.copy(id = 4, kind = Kind.EVENT, calendarEventId = 77L, start = ms + 60 * 60_000)
        val merged = CalendarFeed.merge(listOf(linked), listOf(event(77, "Standup", 60, 90)))
        assertEquals(1, merged.size)
        assertEquals(4L, merged.single().id)
    }

    @Test
    fun `turning the mirror off leaves local items alone`() {
        val merged = CalendarFeed.merge(listOf(taxes), listOf(event(77, "Standup", 60, 90)), include = false)
        assertEquals(listOf(taxes), merged)
    }

    @Test
    fun `all-day rows keep their flag`() {
        val merged = CalendarFeed.merge(emptyList(), listOf(event(9, "Offsite", 0, 1440, allDay = true)))
        assertTrue(merged.single().allDay)
    }

    @Test
    fun `a booked hour is not suggested as free`() {
        val merged = CalendarFeed.merge(listOf(taxes), listOf(event(77, "Standup", 30, 180)))
        val gap = Insights.forDay(merged, now).firstOrNull { it.action is InsightAction.Schedule }
        assertTrue("expected a free-slot suggestion", gap != null)
        // 13:00 + 30 min is booked until 16:00, so the first real hour starts at 16:00.
        assertTrue(gap!!.text.startsWith("16:00"))
    }

    @Test
    fun `a past calendar event is not offered as a loose end`() {
        // Two hours ago, outside Morrow's control.
        val past = event(88, "Old standup", -120, -60)
        val merged = CalendarFeed.merge(emptyList(), listOf(past))
        val overdue = Insights.forDay(merged, now).firstOrNull { it.id == "overdue" }
        assertEquals(null, overdue)
    }

    @Test
    fun `agenda mentions calendar events`() {
        val merged = CalendarFeed.merge(emptyList(), listOf(event(77, "Standup", 60, 90)))
        val agenda = Insights.agendaText(merged, now)
        assertTrue(agenda.contains("Standup"))
        assertTrue(agenda.contains("14:00"))
    }
}
