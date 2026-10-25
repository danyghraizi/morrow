package dev.morrow.llm

/** One function call emitted by the model. */
data class ModelCall(val name: String, val args: Map<String, String>)

/**
 * Parses FunctionGemma's native call syntax:
 * `<start_function_call>call:save{kind:<escape>event<escape>,text:<escape>Dentist<escape>}<end_function_call>`
 *
 * Kept tolerant: if a closing marker is missing (truncated generation) the call
 * is still recovered, because the grammar guarantees the prefix is meaningful.
 */
object CallParser {

    private val COMPLETE_CALL = Regex(
        "<start_function_call>\\s*call:(\\w+)\\s*\\{(.*)\\}\\s*(?:<end_function_call>|\\z)",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val TRUNCATED_CALL = Regex(
        "<start_function_call>\\s*call:(\\w+)\\s*\\{(.*)\\z",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val ARG_RE = Regex("(\\w+)\\s*:\\s*(?:<escape>(.*?)<escape>|([^,}]*))", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String?): ModelCall? {
        if (text.isNullOrBlank()) return null
        val call = COMPLETE_CALL.find(text) ?: TRUNCATED_CALL.find(text) ?: return null
        val name = call.groupValues[1]
        val args = linkedMapOf<String, String>()
        for (arg in ARG_RE.findAll(call.groupValues[2])) {
            val key = arg.groupValues[1]
            // A truncated generation can leave a dangling <escape> before the value.
            val value = arg.groupValues[2].ifEmpty { arg.groupValues[3] }
                .removePrefix("<escape>").removeSuffix("<escape>").trim()
            if (key.isNotEmpty()) args[key] = value
        }
        return ModelCall(name, args)
    }
}
