package dev.morrow.llm

import dev.morrow.data.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the exact prompt and grammars. The golden files were produced by the
 * same builders and then run against the real GGUF (see tools/README.md), so a
 * change here means the model has to be re-validated before shipping.
 */
class FunctionGemmaTest {

    private fun golden(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing golden $name" }
            .bufferedReader().readText()

    @Test
    fun `prompt matches the validated template`() {
        val expected = golden("golden_prompt.txt").trimEnd('\n')
        assertEquals(expected, FunctionGemma.prompt("dentist on friday at 3pm").trimEnd('\n'))
    }

    @Test
    fun `forced grammar only allows the decision the app already made`() {
        val grammar = FunctionGemma.grammar(Kind.EVENT)
        assertEquals(golden("golden_grammar_forced_event.gbnf").trimEnd('\n'), grammar.trimEnd('\n'))
        assertTrue(grammar.contains("<escape>event<escape>"))
        assertFalse(grammar.contains("reply"))
    }

    @Test
    fun `free grammar offers both tools`() {
        assertEquals(golden("golden_grammar_free.gbnf").trimEnd('\n'), FunctionGemma.grammar(null).trimEnd('\n'))
        assertTrue(FunctionGemma.grammar(null).contains("call:reply"))
    }

    @Test
    fun `task hint beats a misplaced enum`() {
        val grammar = FunctionGemma.grammar(Kind.TASK)
        assertTrue(grammar.contains("<escape>task<escape>"))
        assertFalse(grammar.contains("<escape>event<escape>"))
    }

    @Test
    fun `parses a save call`() {
        val call = CallParser.parse(
            "<start_function_call>call:save{kind:<escape>event<escape>,text:<escape>Dentist on friday<escape>}<end_function_call>"
        )
        assertNotNull(call)
        assertEquals("save", call!!.name)
        assertEquals("event", call.args["kind"])
        assertEquals("Dentist on friday", call.args["text"])
    }

    @Test
    fun `parses a reply call`() {
        val call = CallParser.parse("<start_function_call>call:reply{text:<escape>Right.<escape>}<end_function_call>")
        assertEquals("reply", call?.name)
        assertEquals("Right.", call?.args?.get("text"))
    }

    @Test
    fun `recovers a truncated call`() {
        val call = CallParser.parse("<start_function_call>call:save{kind:<escape>task<escape>,text:<escape>Buy mil")
        assertEquals("task", call?.args?.get("kind"))
        assertTrue(call?.args?.get("text")?.startsWith("Buy mil") == true)
    }

    @Test
    fun `ignores garbage`() {
        assertNull(CallParser.parse("I am not sure what you mean."))
        assertNull(CallParser.parse(null))
    }
}
