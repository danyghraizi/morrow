package dev.morrow.domain

import dev.morrow.data.Item
import dev.morrow.data.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The local suggestion engine: no model, so each rule is pinned. */
class InsightsTest {

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 25, 13, 0, 0, 0, ZoneId.of("UTC"))
    private val ms = now.toInstant().toEpochMilli()

    private fun task(title: String, atMinutesFromNow: Long, id: Long) = Item(
        id = id, kind = Kind.TASK, title = title,
        start = ms + atMinutesFromNow * 60_000, engine = "rules", createdAt = ms,
    )

    @Test
    fun `four things today and a clear tomorrow offers to move one`() {
        val items = listOf(
            task("One", 60, 1), task("Two", 120, 2), task("Three", 180, 3), task("Four", 240, 4),
        )
        val insight = Insights.forDay(items, now, English).firstOrNull { it.id == "tomorrow" }
        assertTrue("expected the move-to-tomorrow suggestion", insight != null)
        assertEquals(InsightAction.MoveToTomorrow(4), insight!!.action)
        assertTrue(insight.text.contains("tomorrow", ignoreCase = true))
    }

    @Test
    fun `agenda can answer for tomorrow`() {
        val tomorrow = Item(id = 11, kind = Kind.TASK, title = "Dentist", start = ms + 20 * 3_600_000, engine = "rules")
        val text = Insights.agendaText(listOf(tomorrow), now, English, now.toLocalDate().plusDays(1))
        assertTrue("expected the day label in: $text", text.startsWith("Tomorrow"))
        assertTrue(text.contains("Dentist"))
        assertTrue(text.contains("1 thing"))
        assertFalse(text.contains("Today"))
    }

    @Test
    fun `a clear day talks about the empty day instead`() {
        val insights = Insights.forDay(emptyList(), now, English)
        assertEquals(1, insights.size)
        assertEquals("empty", insights.single().id)
    }

    @Test
    fun `overdue items are only the ones Morrow owns`() {
        val mine = task("Mine", -60, 5)
        val theirs = Item(id = -9, kind = Kind.EVENT, title = "Theirs", start = ms - 3_600_000, end = ms, engine = "calendar")
        val insight = Insights.forDay(listOf(mine, theirs), now, English).firstOrNull { it.id == "overdue" }
        assertEquals(InsightAction.MoveToToday(listOf(5L)), insight!!.action)
    }

    @Test
    fun `suggest text in French uses French copy`() {
        val text = Insights.suggestText(emptyList(), now, French)
        assertTrue(text.contains("Rien d'urgent"))
    }
}
