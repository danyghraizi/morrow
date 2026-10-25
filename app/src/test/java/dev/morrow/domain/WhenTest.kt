package dev.morrow.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The parser is the part of the app that decides when the user's reminder fires,
 * so it is pinned with concrete examples against a fixed clock.
 */
class WhenTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, zone) // Saturday

    private fun at(text: String): ZonedDateTime =
        Instant.ofEpochMilli(When.parse(text, now).start!!).atZone(zone)

    @Test
    fun `tomorrow with an explicit time keeps both`() {
        val spec = When.parse("pay rent tomorrow at 5pm", now)
        assertEquals(ZonedDateTime.of(2026, 10, 25, 17, 0, 0, 0, zone), at("pay rent tomorrow at 5pm"))
        assertTrue(spec.hasDate)
        assertTrue(spec.hasTime)
    }

    @Test
    fun `weekday without a time lands at nine in the morning`() {
        assertEquals(ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, zone), at("report friday"))
    }

    @Test
    fun `weekday with a glued time`() {
        assertEquals(ZonedDateTime.of(2026, 10, 30, 15, 0, 0, 0, zone), at("dentist friday 3pm"))
    }

    @Test
    fun `relative offsets are exact`() {
        assertEquals(ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone), at("in 2 hours"))
        assertEquals(ZonedDateTime.of(2026, 10, 24, 10, 45, 0, 0, zone), at("in 45 minutes"))
    }

    @Test
    fun `bare hour in the afternoon`() {
        assertEquals(ZonedDateTime.of(2026, 10, 24, 17, 0, 0, 0, zone), at("call mom at 5"))
    }

    @Test
    fun `past time rolls to tomorrow`() {
        assertEquals(ZonedDateTime.of(2026, 10, 25, 8, 0, 0, 0, zone), at("standup at 8am"))
    }

    @Test
    fun `duration is captured but does not move the start`() {
        val spec = When.parse("call with Sam at 3pm for 45 minutes", now)
        assertEquals(45, spec.durationMin)
        assertEquals(ZonedDateTime.of(2026, 10, 24, 15, 0, 0, 0, zone), at("call with Sam at 3pm for 45 minutes"))
    }

    @Test
    fun `explicit date later this year`() {
        assertEquals(ZonedDateTime.of(2026, 11, 12, 9, 0, 0, 0, zone), at("flight 12 november"))
    }

    @Test
    fun `explicit date that already passed moves to next year`() {
        assertEquals(ZonedDateTime.of(2027, 5, 3, 9, 0, 0, 0, zone), at("birthday 3 may"))
    }

    @Test
    fun `offset keeps an explicit clock`() {
        assertEquals(ZonedDateTime.of(2026, 10, 27, 9, 0, 0, 0, zone), at("in 3 days at 9am"))
        assertEquals(ZonedDateTime.of(2026, 10, 27, 9, 0, 0, 0, zone), at("dans 3 jours a 9h"))
        // both the offset and the clock are consumed, leaving nothing behind
        assertEquals("", When.cleanTitle("in 3 days at 9am", When.parse("in 3 days at 9am", now)))
        assertTrue(When.parse("in 3 days at 9am", now).hasTime)
    }

    @Test
    fun `deadline phrasing in words and digits`() {
        assertEquals(ZonedDateTime.of(2026, 10, 24, 18, 0, 0, 0, zone), at("grab the dry cleaning before six"))
        assertEquals(ZonedDateTime.of(2026, 10, 24, 17, 0, 0, 0, zone), at("post it before 5"))
        assertEquals(
            "Grab the dry cleaning",
            When.cleanTitle("grab the dry cleaning before six", When.parse("grab the dry cleaning before six", now)),
        )
    }

    @Test
    fun `french day and clock`() {
        assertEquals(ZonedDateTime.of(2026, 10, 25, 15, 0, 0, 0, zone), at("dentiste demain à 15h"))
        assertEquals(ZonedDateTime.of(2026, 10, 30, 15, 0, 0, 0, zone), at("dentiste vendredi à 15h"))
        assertEquals(ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, zone), at("réunion vendredi"))
    }

    @Test
    fun `french glued and offset forms`() {
        assertEquals(ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone), at("dans 2 heures"))
        assertEquals(ZonedDateTime.of(2026, 10, 24, 18, 0, 0, 0, zone), at("courses avant 18h"))
        assertEquals(ZonedDateTime.of(2026, 10, 24, 20, 30, 0, 0, zone), at("dîner à 20h30"))
        assertEquals(ZonedDateTime.of(2026, 10, 24, 14, 0, 0, 0, zone), at("déjeuner à 14h"))
    }

    @Test
    fun `french dates and duration`() {
        assertEquals(ZonedDateTime.of(2026, 11, 12, 9, 0, 0, 0, zone), at("vol le 12 novembre"))
        val spec = When.parse("appel avec Sam à 15h pendant 45 minutes", now)
        assertEquals(45, spec.durationMin)
        assertEquals(ZonedDateTime.of(2026, 10, 24, 15, 0, 0, 0, zone), at("appel avec Sam à 15h pendant 45 minutes"))
    }

    @Test
    fun `french titles are cleaned`() {
        assertEquals(
            "Payer le loyer",
            When.cleanTitle("rappelle-moi de payer le loyer demain à 15h", When.parse("rappelle-moi de payer le loyer demain à 15h", now)),
        )
        assertEquals(
            "Dentiste",
            When.cleanTitle("dentiste vendredi à 15h", When.parse("dentiste vendredi à 15h", now)),
        )
    }

    @Test
    fun `accent-free typing still parses`() {
        assertEquals(ZonedDateTime.of(2026, 10, 25, 15, 0, 0, 0, zone), at("dentiste demain a 15h"))
        assertEquals(ZonedDateTime.of(2026, 10, 30, 9, 0, 0, 0, zone), at("reunion vendredi"))
    }

    @Test
    fun `no time at all`() {
        val spec = When.parse("buy stamps", now)
        assertNull(spec.start)
        assertTrue(spec.spans.isEmpty())
    }

    @Test
    fun `titles lose the time words and the filler`() {
        assertEquals("Pay rent", When.cleanTitle("remind me to pay rent tomorrow at 5pm", When.parse("remind me to pay rent tomorrow at 5pm", now)))
        assertEquals("Dentist", When.cleanTitle("dentist on friday at 3pm", When.parse("dentist on friday at 3pm", now)))
        assertEquals("Call the bank", When.cleanTitle("i have to call the bank", When.parse("i have to call the bank", now)))
        assertEquals("Meeting with Anna", When.cleanTitle("meeting with Anna at ten for an hour", When.parse("meeting with Anna at ten for an hour", now)))
    }
}
