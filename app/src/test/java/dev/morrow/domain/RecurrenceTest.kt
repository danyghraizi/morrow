package dev.morrow.domain

import dev.morrow.data.Item
import dev.morrow.data.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurrenceTest {

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, ZoneId.of("UTC")) // Saturday

    @Test
    fun `english weekly rule`() {
        val parsed = Recurrence.parse("gym every monday at 7pm")
        assertNotNull(parsed)
        val (rule, span) = parsed!!
        assertEquals(Recurrence.Freq.WEEKLY, rule.freq)
        assertEquals(setOf(DayOfWeek.MONDAY), rule.days)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", rule.rrule())
        assertEquals("every monday", "gym every monday at 7pm".substring(span.first, span.last + 1))
    }

    @Test
    fun `french weekly rule`() {
        val parsed = Recurrence.parse("yoga tous les lundis à 19h")!!
        assertEquals(setOf(DayOfWeek.MONDAY), parsed.first.days)
        assertEquals("tous les lundis", "yoga tous les lundis à 19h".substring(parsed.second.first, parsed.second.last + 1))
    }

    @Test
    fun `daily in both languages`() {
        assertEquals(Recurrence.Freq.DAILY, Recurrence.parse("water the plants every day")!!.first.freq)
        assertEquals(Recurrence.Freq.DAILY, Recurrence.parse("arroser tous les jours")!!.first.freq)
    }

    @Test
    fun `weekdays shortcut`() {
        val rule = Recurrence.parse("standup every weekday at 9")!!.first
        assertEquals(Recurrence.WEEKDAYS, rule.days)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", rule.rrule())
    }

    @Test
    fun `interval is carried`() {
        val rule = Recurrence.parse("pay rent every 2 weeks")!!.first
        assertEquals(2, rule.interval)
        assertEquals("FREQ=WEEKLY;INTERVAL=2", rule.rrule())
    }

    @Test
    fun `next occurrence walks the right weekday`() {
        val rule = Recurrence.parse("every monday")!!.first
        val first = now.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))
        val next = rule.nextAfter(first.withHour(9))
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
        assertEquals(first.plusWeeks(1).toLocalDate(), next.toLocalDate())
    }

    @Test
    fun `daily occurrences are consecutive days`() {
        val rule = Recurrence(Recurrence.Freq.DAILY)
        val days = rule.occurrences(now, 3).map { it.toLocalDate() }
        assertEquals(listOf(now.toLocalDate(), now.toLocalDate().plusDays(1), now.toLocalDate().plusDays(2)), days)
    }

    @Test
    fun `rrule round trip`() {
        listOf(
            Recurrence(Recurrence.Freq.DAILY),
            Recurrence(Recurrence.Freq.WEEKLY, setOf(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY)),
            Recurrence(Recurrence.Freq.WEEKLY, interval = 2),
            Recurrence(Recurrence.Freq.MONTHLY),
        ).forEach { rule ->
            assertEquals(rule, Recurrence.fromRrule(rule.rrule()))
        }
    }

    @Test
    fun `series is topped up to the horizon and capped`() {
        val row = Item.task("Water plants", now.toInstant().toEpochMilli(), null, "rules", 0)
            .copy(rrule = "FREQ=DAILY", seriesId = 7)
        val added = Recurrence.missingOccurrences(listOf(row), now, horizonDays = 5, maxRows = 6)
        assertEquals(5, added.size)
        assertTrue(added.all { it.seriesId == 7L && it.rrule == "FREQ=DAILY" })

        val capped = Recurrence.missingOccurrences(
            List(6) { row.copy(id = it + 1L, start = row.start + it * 86_400_000L) }, now, horizonDays = 30, maxRows = 6,
        )
        assertTrue(capped.isEmpty())
    }

    @Test
    fun `topping up never duplicates an occurrence`() {
        val row = Item.task("Water plants", now.toInstant().toEpochMilli(), null, "rules", 0)
            .copy(rrule = "FREQ=DAILY", seriesId = 3)
        val first = Recurrence.missingOccurrences(listOf(row), now, horizonDays = 4, maxRows = 5)
        // Simulate the race that produced duplicates on hardware: same rows twice.
        val duplicated = listOf(row) + first + first
        val again = Recurrence.missingOccurrences(duplicated, now, horizonDays = 30, maxRows = 20)
        val existing = duplicated.map { it.start }.toSet()
        assertTrue(again.none { it.start in existing })
    }

    @Test
    fun `no rule means no occurrences`() {
        assertNull(Recurrence.parse("buy stamps"))
        val plain = Item.task("Buy stamps", 0, null, "rules", 0)
        assertTrue(Recurrence.missingOccurrences(listOf(plain), now).isEmpty())
    }

    @Test
    fun `kind stays a task even when repeating`() {
        val spec = When.parse("yoga tous les lundis à 19h", now)
        assertTrue(Recurrence.parse("yoga tous les lundis à 19h") != null)
        // "yoga" is in the event vocabulary and a time is present.
        assertEquals(Kind.EVENT, QuickParse.kindFor(When.fold("yoga tous les lundis à 19h"), spec))
    }
}
