package dev.morrow.domain

import dev.morrow.data.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QuickParseTest {

    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 24, 10, 0, 0, 0, ZoneId.of("UTC"))

    @Test
    fun `completion by prefix and suffix`() {
        val prefix = QuickParse.route("done with the rent", now)
        assertTrue(prefix is Route.Complete)
        assertEquals("the rent", (prefix as Route.Complete).query)

        val suffix = QuickParse.route("the rent is done", now)
        assertTrue(suffix is Route.Complete)
        assertEquals("the rent", (suffix as Route.Complete).query)
    }

    @Test
    fun `reschedule keeps the target and a fresh time`() {
        val route = QuickParse.route("move the dentist to thursday", now)
        assertTrue(route is Route.Move)
        route as Route.Move
        assertEquals("The dentist", route.query)
        val at = java.time.Instant.ofEpochMilli(route.spec.start!!).atZone(ZoneId.of("UTC"))
        assertEquals(java.time.DayOfWeek.THURSDAY, at.dayOfWeek)
        assertEquals(29, at.dayOfMonth)
    }

    @Test
    fun `agenda and suggestions are answered locally`() {
        assertTrue(QuickParse.route("what's on my plate", now) is Route.Agenda)
        assertTrue(QuickParse.route("what should i do now", now) is Route.Suggest)
        assertTrue(QuickParse.route("help", now) is Route.Help)
    }

    @Test
    fun `notes are captured verbatim`() {
        val route = QuickParse.route("remember that I hate morning meetings", now)
        assertTrue(route is Route.Remember)
        assertEquals("I hate morning meetings", (route as Route.Remember).text)
    }

    @Test
    fun `small talk stays local`() {
        assertTrue(QuickParse.route("hi", now) is Route.SmallTalk)
        assertTrue(QuickParse.route("thanks!", now) is Route.SmallTalk)
    }

    @Test
    fun `an appointment with a time is an event`() {
        val route = QuickParse.route("dentist on friday at 3pm", now)
        assertTrue(route is Route.Add)
        route as Route.Add
        assertEquals(Kind.EVENT, route.kind)
        assertEquals("Dentist", route.title)
    }

    @Test
    fun `an errand with a time is a task`() {
        val route = QuickParse.route("pick up dry cleaning tomorrow at 5", now)
        assertTrue(route is Route.Add)
        route as Route.Add
        assertEquals(Kind.TASK, route.kind)
        assertEquals("Pick up dry cleaning", route.title)
    }

    @Test
    fun `explicit add without a time is a task`() {
        val route = QuickParse.route("remind me to call the bank", now)
        assertTrue(route is Route.Add)
        route as Route.Add
        assertEquals(Kind.TASK, route.kind)
        assertEquals("Call the bank", route.title)
        assertNull(route.spec.start)
    }

    @Test
    fun `deadline phrasing stays on the rules path`() {
        val route = QuickParse.route("grab the dry cleaning before six", now)
        assertTrue(route is Route.Add)
        route as Route.Add
        assertEquals(Kind.TASK, route.kind)
        assertEquals("Grab the dry cleaning", route.title)
        assertEquals(true, route.spec.start != null)
    }

    @Test
    fun `french add with an appointment is an event`() {
        val route = QuickParse.route("dentiste vendredi à 15h", now)
        assertTrue(route is Route.Add)
        route as Route.Add
        assertEquals(Kind.EVENT, route.kind)
        assertEquals("Dentiste", route.title)
    }

    @Test
    fun `french task and completion`() {
        val add = QuickParse.route("rappelle-moi de payer le loyer demain", now)
        assertTrue(add is Route.Add)
        assertEquals("Payer le loyer", (add as Route.Add).title)

        val done = QuickParse.route("le loyer est fait", now)
        assertTrue(done is Route.Complete)
        assertEquals("le loyer", (done as Route.Complete).query)

        val moved = QuickParse.route("déplace le dentiste à jeudi", now)
        assertTrue(moved is Route.Move)
        assertEquals("Le dentiste", (moved as Route.Move).query)
    }

    @Test
    fun `french questions and notes`() {
        assertTrue(QuickParse.route("qu'est-ce que j'ai aujourd'hui", now) is Route.Agenda)
        assertTrue(QuickParse.route("que dois-je faire", now) is Route.Suggest)
        assertTrue(QuickParse.route("aide", now) is Route.Help)
        val note = QuickParse.route("retiens que je préfère le matin", now)
        assertTrue(note is Route.Remember)
        assertEquals("Je préfère le matin", (note as Route.Remember).text)
    }

    @Test
    fun `repetition words are removed from the title`() {
        val route = QuickParse.route("gym every monday at 7pm", now)
        assertTrue(route is Route.Add)
        assertEquals("Gym", (route as Route.Add).title)
        assertTrue(Recurrence.parse("gym every monday at 7pm") != null)
    }

    @Test
    fun `a time with no words asks for a name`() {
        val route = QuickParse.route("dans 3 jours a 9h", now)
        assertTrue(route is Route.NeedsTitle)
        assertEquals(true, (route as Route.NeedsTitle).spec.start != null)
    }

    @Test
    fun `already done reads as completion in both languages`() {
        val fr = QuickParse.route("j'ai déjà payé le loyer", now)
        assertTrue(fr is Route.Complete)
        assertEquals("le loyer", (fr as Route.Complete).query)

        val en = QuickParse.route("i already paid the rent", now)
        assertTrue(en is Route.Complete)
        assertEquals("the rent", (en as Route.Complete).query)
    }

    @Test
    fun `agenda question keeps its day`() {
        val route = QuickParse.route("what is on my plate tomorrow", now)
        assertTrue(route is Route.Agenda)
        // the day is resolved inside the assistant from the same sentence
        assertTrue(When.parse("what is on my plate tomorrow", now).hasDate)
    }

    @Test
    fun `two actions in one sentence are split`() {
        val en = QuickParse.splitActions("pay the rent and call the dentist tomorrow")
        assertEquals(listOf("pay the rent", "call the dentist tomorrow"), en)

        val fr = QuickParse.splitActions("payer le loyer et appeler le dentiste demain")
        assertEquals(listOf("payer le loyer", "appeler le dentiste demain"), fr)
    }

    @Test
    fun `noun lists and appointments are not split`() {
        assertEquals(null, QuickParse.splitActions("buy milk and eggs"))
        assertEquals(null, QuickParse.splitActions("meeting with Anna and Sam at 3"))
        assertEquals(null, QuickParse.splitActions("call mom and dad"))
    }

    @Test
    fun `cancel is refused instead of silently becoming a task`() {
        assertTrue(QuickParse.route("cancel my gym session", now) is Route.Unsupported)
        assertTrue(QuickParse.route("supprimer le rendez-vous", now) is Route.Unsupported)
    }

    @Test
    fun `bare noun phrases are left to the model`() {
        assertNull(QuickParse.route("buy stamps", now))
        assertNull(QuickParse.route("renew the passport", now))
    }

    @Test
    fun `questions are never filed as items`() {
        assertNull(QuickParse.route("what time is my dentist appointment friday", now))
    }

    @Test
    fun `duration makes it an event`() {
        val spec = When.parse("call with Sam at 3pm for 45 minutes", now)
        assertEquals(Kind.EVENT, QuickParse.kindFor("call with sam at 3pm for 45 minutes", spec))
    }
}
