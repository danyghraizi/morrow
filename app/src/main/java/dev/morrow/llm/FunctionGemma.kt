package dev.morrow.llm

import dev.morrow.data.Kind

/**
 * Prompt and grammar for FunctionGemma 270M.
 *
 * Two things make this small model usable:
 *  1. It gets exactly one job per request: turn a sentence into `save(kind,text)`
 *     or `reply(text)`. No date arithmetic, no memory lookups, no chit-chat
 *     planning.
 *  2. The app decides `kind` whenever it can and folds that decision into the
 *     grammar, so the model physically cannot pick the wrong one. The output is
 *     additionally constrained to the model's native call syntax, which removes
 *     every malformed-generation retry.
 *
 * The declaration block mirrors the model's own chat template
 * (sorted keys, <escape> markers), verified against the bundled GGUF.
 */
object FunctionGemma {

    const val START_CALL = "<start_function_call>"
    const val END_CALL = "<end_function_call>"

    private const val E = "<escape>"

    private fun esc(value: String) = E + value + E

    /** Rendered exactly as the FunctionGemma chat template renders tool declarations. */
    val declarations: String = buildString {
        append("<start_function_declaration>declaration:save\n")
        append("{description:${esc("Stores one thing the user said.")}")
        append(",parameters:{properties:{")
        append("kind:{description:${esc("task for something to do, event for a meeting, note for a lasting fact.")}")
        append(",enum:${esc("task")},${esc("event")},${esc("note")},type:${esc("STRING")}}")
        append(",text:{description:${esc("The words of the user, with time words left out.")},type:${esc("STRING")}}")
        append("},required:[${esc("kind")},${esc("text")}],type:${esc("OBJECT")}}}")
        append("<end_function_declaration>")

        append("<start_function_declaration>declaration:reply\n")
        append("{description:${esc("Answers the user in plain words.")}")
        append(",parameters:{properties:{text:{description:${esc("A short answer.")},type:${esc("STRING")}}}")
        append(",required:[${esc("text")}],type:${esc("OBJECT")}}}")
        append("<end_function_declaration>")
    }

    fun prompt(utterance: String): String = buildString {
        append("<start_of_turn>developer\n")
        append("You are a model that can do function calling with the following functions")
        append(declarations)
        append("<end_of_turn>\n")
        append("<start_of_turn>user\n")
        append(utterance.trim())
        append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    /**
     * @param forcedKind when the app already knows this is a task or an event,
     * only that alternative is reachable.
     */
    fun grammar(forcedKind: Kind?): String = buildString {
        append("root ::= call\n")
        if (forcedKind == null) {
            append("call ::= \"$START_CALL" + "call:save{kind:\" kind \",text:\" str \"}\" \"$END_CALL\"")
            append(" | \"$START_CALL" + "call:reply{text:\" str \"}\" \"$END_CALL\"\n")
            append("kind ::= \"${esc("task")}\" | \"${esc("event")}\" | \"${esc("note")}\"\n")
        } else {
            val name = if (forcedKind == Kind.EVENT) "event" else "task"
            append("call ::= \"$START_CALL" + "call:save{kind:\" \"${esc(name)}\" \",text:\" str \"}\" \"$END_CALL\"\n")
        }
        append("str ::= \"$E\" body \"$E\"\n")
        append("body ::= [^<]+\n")
    }
}
