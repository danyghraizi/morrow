package dev.morrow.domain

import dev.morrow.data.Kind
import java.time.ZonedDateTime
import java.util.Locale

/** A deterministic reading of what the user meant, or null when it is too open. */
sealed interface Route {
    data class Add(val title: String, val spec: TimeSpec, val kind: Kind) : Route
    data class Complete(val query: String) : Route
    data class Move(val query: String, val spec: TimeSpec) : Route
    data class Remember(val text: String) : Route
    data object Agenda : Route
    data object Suggest : Route
    data object Help : Route
    data class SmallTalk(val reply: String) : Route
    /** Cancelling/deleting is not something the assistant can do. */
    data object Unsupported : Route

    /** A time with no words left ("dans 3 jours à 9h"): ask what it is. */
    data class NeedsTitle(val spec: TimeSpec) : Route
}

/**
 * The fast path, in English and French.
 *
 * Everything an assistant is asked to do most of the time is a handful of verb
 * patterns, and answering them here costs no model, no battery and no latency.
 * Only sentences this cannot place reach the on-device LLM.
 */
object QuickParse {

    private val ADD_PREFIX = Regex(
        "^(remind me( to)?|remind|add( a| an| the)?( task| event| appointment)?|schedule|put|" +
            "i (have|need|must|got|gotta) to|don't forget to|do not forget to|don't forget|" +
            "note down|jot|set a reminder( to| for)?|save|" +
            "rappelle-moi( de| d')?|rappelez-moi( de| d')?|rappelle moi( de| d')?|" +
            "pense a|penser a|n'oublie pas de|noublie pas de|il faut que je|il faut|" +
            "je dois|je doit|j'ai|j ai|ajoute|ajouter|mets|programme|planifie|prevoir|prevoyez|organise)\\b"
    )
    private val COMPLETE_PREFIX = Regex(
        "^(done with|finished with|finished|complete|completed|mark|tick off|ticked off|check off|" +
            "checked off|archive|cross off|fini avec|fini|termine|terminee|j'ai fini|j ai fini|" +
            "c'est fait|cest fait|fait|coche|valide|marque)\\b"
    )
    /** "j'ai déjà payé le loyer", "i already paid the rent": a done thing, not a new one. */
    private val ALREADY_DONE = Regex(
        """^j\s*'?\s*ai\s+deja\s+(?:paye|fini|fait|appele|envoye|achete|pris|vu|reserve|termine|regle|rendu)\s+(.+)$""" +
            """|^i\s+(?:already\s+)?(?:paid|finished|sent|called|booked|did|bought|picked)\s+(.+)$"""
    )
    private val COMPLETE_SUFFIX = Regex(
        "\\b(is|are|was|now) (done|finished|complete|off the list)$|\\b(est|sont) (fait|faite|fini|finie|termine|terminee)$"
    )
    private val MOVE_PREFIX = Regex(
        "^(move|push|postpone|reschedule|delay|defer|shift|bump|bring forward|deplace|deplacer|" +
            "reporte|reporter|decale|decaler|repousse|avance|change)\\b"
    )
    private val REMEMBER_PREFIX = Regex(
        "^(remember|keep in mind|don't forget|do not forget|retiens|souviens-toi|souviens toi|" +
            "garde en tete)\\b"
    )
    private val NOTE_PREFIX = Regex("^(note that|note bien que|note)\\b")

    private val EVENT_WORDS = listOf(
        "meeting", "appointment", "appt", "dentist", "doctor", "lunch", "dinner", "breakfast",
        "brunch", "coffee with", "call with", "flight", "train", "bus", "class", "lesson",
        "lecture", "interview", "haircut", "gym", "yoga", "pilates", "party", "birthday",
        "wedding", "concert", "movie", "cinema", "reservation", "standup", "stand-up", "sync",
        "1:1", "one on one", "review with", "checkup", "check-up", "ceremony", "conference",
        "workshop", "therapy", "physio", "viewing", "open house",
        "rendez-vous", "rendez vous", "reunion", "réunion", "dejeuner", "diner", "petit-dejeuner",
        "vol", "cours", "entretien", "coiffeur", "dentiste", "medecin", "anniversaire", "mariage",
        "concert", "cinema", "reservation", "visite", "consultation", "yoga",
    )

    private val HELP_WORDS = listOf(
        "what can you do", "who are you", "how does this work", "how do i use", "what are you",
        "help", "what should i say",
        "aide", "que peux-tu faire", "que peux tu faire", "comment ca marche", "comment ça marche",
        "tu sais faire quoi", "que sais-tu faire",
    )
    private val SUGGEST_WORDS = listOf(
        "what should i do", "what can i do", "suggest", "any ideas", "help me plan",
        "free time", "i have free", "anything i should", "what now", "i'm bored", "im bored",
        "que dois-je faire", "que faire", "des idees", "des idées", "suggere", "propose",
        "je m'ennuie", "par quoi commencer",
    )
    private val AGENDA_WORDS = listOf(
        "what's on my plate", "whats on my plate", "what is on my plate", "my agenda",
        "my schedule", "my day", "what's next", "whats next", "what is next", "what do i have",
        "what's today", "whats today", "anything today", "anything tomorrow", "agenda",
        "what does my day look like", "what's my day",
        "qu'est-ce que j'ai", "qu est ce que j ai", "quest-ce que jai", "mon programme",
        "mon agenda", "ma journee", "ma journée", "mon planning", "quoi de prevu", "quoi de prévu",
        "c'est quoi mon", "aujourd'hui ?", "j'ai quoi",
    )

    val ACTION_VERBS = listOf(
        "buy", "call", "send", "email", "book", "finish", "pick", "pay", "write", "clean",
        "fix", "meet", "plan", "prepare", "read", "renew", "return", "submit", "order",
        "cancel", "visit", "drop", "collect", "check", "review", "apply", "sign", "water",
        "feed", "bring", "ask", "tell", "remind", "cancel", "print", "post",
        "acheter", "appeler", "envoyer", "payer", "finir", "prendre", "ranger", "nettoyer",
        "reparer", "ecrire", "imprimer", "chercher", "ramener", "reserver", "annuler",
        "rappeler", "rendre", "poster", "commander", "preparer", "lire",
    )
    private val QUESTION_RE = Regex(
        "^(what|when|where|who|why|how|do i|does|should i|can you|could you|is there|are there|" +
            "am i|will i|did i|tell me|quand|ou est|où est|combien|pourquoi|qui|est-ce que|" +
            "quelle heure)\\b"
    )
    private val GREETING = Regex("^(hi|hello|hey|yo|hiya|good morning|morning|good evening|evening|bonjour|salut|bonsoir|coucou)\\b.{0,20}$")
    private val THANKS = Regex("^(thanks|thank you|thx|cheers|ty|merci|merci beaucoup)\\b.{0,20}$")
    private val ACK = Regex("^(ok|okay|k|cool|nice|great|perfect|got it|alright|sure|d'accord|daccord|super|parfait|ca marche|ça marche)\\b.{0,20}$")
    private val FAREWELL = Regex("^(bye|goodbye|see you|good night|night|a plus|à plus|bonne nuit|a demain)\\b.{0,20}$")

    fun route(raw: String, now: ZonedDateTime = ZonedDateTime.now()): Route? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        // Accent-folded copy keeps indices aligned, so slices still work below.
        val lower = When.fold(text)

        smallTalk(lower)?.let { return Route.SmallTalk(it) }
        if (HELP_WORDS.any { lower == it || lower.startsWith("$it ") }) return Route.Help
        if (SUGGEST_WORDS.any { lower.contains(it) }) return Route.Suggest
        if (AGENDA_WORDS.any { lower.contains(it) }) return Route.Agenda
        // A question is never something to file away.
        if (QUESTION_RE.containsMatchIn(lower)) return null

        if (Regex("^(cancel|delete|remove|annuler|supprimer|effacer)\\b").containsMatchIn(lower)) {
            return Route.Unsupported
        }

        ALREADY_DONE.find(lower)?.let { m ->
            // This pattern captures the thing that is done; the other prefixes do not.
            val tail = m.groups.drop(1).firstOrNull { it != null } ?: return@let
            val rest = text.substring(tail.range.first, tail.range.last + 1).trim().removeSuffix(".").trim()
            if (rest.isNotEmpty()) return Route.Complete(rest)
        }
        COMPLETE_PREFIX.find(lower)?.let { m ->
            val rest = text.substring(m.range.last + 1).trim().removeSuffix(".").trim()
            if (rest.isNotEmpty()) return Route.Complete(rest)
        }
        if (COMPLETE_SUFFIX.containsMatchIn(lower)) {
            return Route.Complete(text.replace(COMPLETE_SUFFIX, "").trim())
        }
        MOVE_PREFIX.find(lower)?.let { m ->
            val rest = text.substring(m.range.last + 1).trim().removeSuffix(".").trim()
            if (rest.isEmpty()) return null
            val spec = When.parse(rest, now)
            val query = When.cleanTitle(rest, spec).ifBlank { rest }
            return Route.Move(query, spec)
        }
        REMEMBER_PREFIX.find(lower)?.let { m ->
            val rest = text.substring(m.range.last + 1).trim()
                .removePrefix("that")
                .removePrefix("que ")
                .removePrefix("qu'")
                .removeSuffix(".")
                .trim()
            if (rest.isNotEmpty()) return Route.Remember(When.tidy(rest))
        }
        NOTE_PREFIX.find(lower)?.let { m ->
            val rest = text.substring(m.range.last + 1).trim().removeSuffix(".").trim()
            if (rest.isNotEmpty()) return Route.Remember(When.tidy(rest))
        }

        val spec = When.parse(text, now)
        // Repetition words ("every monday") belong to the rule, not the title.
        val recurrence = Recurrence.parse(text)
        val effective = if (recurrence != null) spec.copy(spans = spec.spans + listOf(recurrence.second)) else spec
        val explicit = ADD_PREFIX.containsMatchIn(lower)
        // Anything with a resolvable time is placed by rules alone. The model is
        // reserved for sentences where neither the verb nor the time is obvious.
        if (explicit || effective.start != null) {
            val title = When.cleanTitle(text, effective)
            if (title.isBlank()) return Route.NeedsTitle(effective)
            return Route.Add(title, effective, kindFor(lower, effective))
        }
        return null
    }

    private fun smallTalk(lower: String): String? {
        val core = lower.trim().trim('.', '!', '?', ',')
        val copy = Copy.current()
        return when {
            core.matches(GREETING) -> copy.helloReply()
            core.matches(THANKS) -> copy.anytimeReply()
            core.matches(ACK) -> copy.ackReply()
            core.matches(FAREWELL) -> copy.farewellReply()
            else -> null
        }
    }

    /**
     * Two actions in one sentence ("pay the rent and call the dentist") is the
     * most common thing people write. Splitting is only attempted when *both*
     * halves contain an action verb, so "buy milk and eggs" or "meeting with Anna
     * and Sam" stay one item.
     */
    fun splitActions(raw: String): List<String>? {
        val text = raw.trim()
        val folded = When.fold(text)
        val verbs = ACTION_VERBS
        for (joiner in listOf(" and ", " et ", " puis ", " then ")) {
            val at = folded.indexOf(joiner)
            if (at <= 0) continue
            val left = text.substring(0, at).trim()
            val right = text.substring(at + joiner.length).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            val bothAct = verbs.any { folded.contains(it) } &&
                verbs.any { When.fold(left).contains(it) } &&
                verbs.any { When.fold(right).contains(it) }
            if (!bothAct) continue
            // Each half must survive title cleaning; otherwise it is not an action.
            if (When.cleanTitle(left, When.parse(left)).isBlank()) continue
            if (When.cleanTitle(right, When.parse(right)).isBlank()) continue
            return listOf(left, right)
        }
        return null
    }

    /** Event vs task, decided by vocabulary rather than by a 270M coin flip. */
    fun kindFor(lower: String, spec: TimeSpec): Kind {
        val eventish = EVENT_WORDS.any { lower.contains(it) }
        return when {
            eventish && (spec.hasTime || spec.hasDate) -> Kind.EVENT
            spec.durationMin != null -> Kind.EVENT
            else -> Kind.TASK
        }
    }
}
