package dev.morrow.domain

import dev.morrow.calendar.CalendarFeed
import dev.morrow.calendar.CalendarWriter
import dev.morrow.data.Item
import dev.morrow.data.Kind
import dev.morrow.data.Repo
import dev.morrow.data.Settings
import dev.morrow.llm.CallParser
import dev.morrow.llm.FunctionGemma
import dev.morrow.llm.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

/**
 * The assistant's brain, deliberately split in three:
 *
 *  1. [QuickParse] handles the ~80% of sentences that are a verb pattern
 *     ("remind me to…", "done with…", "what's today"), in English or French.
 *     No model, ~1 ms.
 *  2. [When] and [Recurrence] resolve time words and repetition into instants and
 *     iCalendar rules. This is the part a 270M model gets wrong, so it is code.
 *  3. Only leftovers reach the LLM, and the app folds its own decision about
 *     task/event/note into the grammar so the model cannot drift.
 */
class Assistant(
    private val repo: Repo,
    private val engine: LlamaEngine,
    private val settings: Settings,
    private val calendar: CalendarWriter,
    private val feed: CalendarFeed,
    private val copy: Copy = Copy.current(),
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    data class Reply(
        val text: String,
        /** "rules", "model", "model-offline" or "app"; shown in the UI for honesty. */
        val engine: String,
        val item: Item? = null,
        /** True when the reply changed stored data, so the UI may offer Undo. */
        val mutated: Boolean = false,
    )

    suspend fun respond(raw: String): Reply {
        val text = raw.trim()
        if (text.isEmpty()) return Reply(copy.agendaEmpty(), "app")
        val now = clock()
        // "pay the rent and call the dentist" is the most common way people write
        // two things; only split when both halves are clearly actions.
        val parts = QuickParse.splitActions(text)
        val reply = if (parts != null) {
            val first = handle(parts[0], now)
            val second = handle(parts[1], now)
            Reply(
                text = "${first.text}\n${second.text}",
                engine = if (first.engine == "rules" && second.engine == "rules") "rules" else "app",
                item = second.item ?: first.item,
                mutated = first.mutated || second.mutated,
            )
        } else {
            handle(text, now)
        }
        val finalReply = maybeRepeatNote(text, reply)
        repo.logTurn(text, finalReply.text, finalReply.engine)
        return finalReply
    }

    private suspend fun handle(text: String, now: ZonedDateTime): Reply {
        val route = QuickParse.route(text, now)
        return when (route) {
            is Route.Add -> add(text, route.title, route.spec, route.kind, "rules", now)
            is Route.Complete -> complete(route.query)
            is Route.Move -> move(route.query, route.spec, now)
            is Route.Remember -> remember(route.text)
            Route.Agenda -> {
                val spec = When.parse(text, now)
                val start = spec.start
                val day = if (spec.hasDate && start != null) {
                    Instant.ofEpochMilli(start).atZone(now.zone).toLocalDate()
                } else now.toLocalDate()
                Reply(Insights.agendaText(timeline(), now, copy, day), "rules")
            }
            is Route.NeedsTitle -> {
                val start = route.spec.start
                Reply(copy.askNameWithTime(copy.whenText(start ?: 0L, now)), "rules")
            }
            Route.Suggest -> Reply(Insights.suggestText(timeline(), now, copy), "rules")
            Route.Help -> Reply(copy.helpText(), "app")
            is Route.SmallTalk -> Reply(route.reply, "app")
            Route.Unsupported -> Reply(copy.cannotCancel(), "app")
            null -> think(text, now)
        }
    }

    /**
     * Recurrence is deliberately small (daily / weekdays / weekly / monthly). When
     * the user asks for something else, say so instead of silently saving a one-off.
     */
    private fun maybeRepeatNote(text: String, reply: Reply): Reply {
        if (!reply.mutated) return reply
        val folded = When.fold(text)
        val askedToRepeat = listOf("every", "each", "tous les", "toutes les", "chaque", "quotidien")
            .any { folded.contains(it) }
        if (!askedToRepeat || Recurrence.parse(text) != null) return reply
        return reply.copy(text = reply.text + "\n" + copy.cannotRepeat())
    }

    /** Everything the user will actually experience today, calendar included. */
    private fun timeline(): List<Item> =
        CalendarFeed.merge(repo.snapshot.value.items, feed.events.value, settings.showCalendar)

    private suspend fun think(text: String, now: ZonedDateTime): Reply {
        val spec = When.parse(text, now)
        val lower = When.fold(text)
        val actionable = spec.start != null || spec.durationMin != null || ACTION_VERBS.any { lower.contains(it) }
        val hint: Kind? = if (actionable) QuickParse.kindFor(lower, spec) else null

        // The user's own words are usually a perfectly good title: "pay the rent"
        // needs no model, and asking one to "save" it produced refusals like
        // "I am sorry, but I cannot assist with paying the rent right now."
        val cleaned = When.cleanTitle(text, spec)
        val words = cleaned.split(Regex("[\\s]+")).filter { it.isNotBlank() }
        if (hint != null && cleaned.isNotBlank() && words.size <= 10 &&
            ACTION_VERBS.any { lower.contains(it) } && Recurrence.parse(text) == null
        ) {
            return add(text, cleaned, spec, hint, "rules", now)
        }

        if (!settings.useModel) return fallback(text, spec, hint, now, "model-offline")

        val raw = engine.generate(FunctionGemma.prompt(text), FunctionGemma.grammar(hint))
        val call = CallParser.parse(raw)
            ?: return fallback(text, spec, hint, now, "model")

        return when (call.name) {
            "save" -> {
                val kind = when (call.args["kind"]?.lowercase(Locale.US)) {
                    "event" -> Kind.EVENT
                    "note" -> null
                    else -> Kind.TASK
                }
                val modelText = call.args["text"] ?: ""
                val repeatSpan = Recurrence.parse(modelText)?.second
                val modelSpec = When.parse(modelText, now).let { parsed ->
                    if (repeatSpan != null) parsed.copy(spans = parsed.spans + listOf(repeatSpan)) else parsed
                }
                val fromModel = When.cleanTitle(modelText, modelSpec)
                // Ignore anything that does not actually echo the user's words.
                val body = if (acceptableTitle(fromModel, text)) fromModel else When.cleanTitle(text, spec)
                if (kind == null) remember(body)
                else add(text, body, spec, kind, "model", now)
            }
            "reply" -> {
                val answer = (call.args["text"] ?: "").trim().take(500)
                if (answer.isEmpty()) fallback(text, spec, hint, now, "model")
                else Reply(answer, "model")
            }
            else -> fallback(text, spec, hint, now, "model")
        }
    }

    /**
     * When the model is unavailable or unparseable, never lose the user's words:
     * an explicit add becomes a task, anything else becomes a short question.
     */
    private suspend fun fallback(text: String, spec: TimeSpec, hint: Kind?, now: ZonedDateTime, engineName: String): Reply {
        val title = When.cleanTitle(text, spec)
        return if (hint != null && title.isNotBlank()) {
            add(text, title, spec, hint, engineName, now)
        } else {
            Reply(copy.couldNotCatch(), engineName)
        }
    }

    private suspend fun add(
        source: String,
        title: String,
        spec: TimeSpec,
        kind: Kind,
        engineName: String,
        now: ZonedDateTime,
    ): Reply {
        val nowMs = now.toInstant().toEpochMilli()
        val clean = When.tidy(title).ifBlank { When.tidy(source) }
        if (clean.isBlank()) return Reply(copy.askName(), engineName)

        val start = spec.start ?: 0L
        // An event needs a slot; without a time it is really a to-do.
        val effectiveKind = if (kind == Kind.EVENT && start == 0L) Kind.TASK else kind

        val recurrence = Recurrence.parse(source)?.first
        if (recurrence != null) {
            return addSeries(source, clean, spec, effectiveKind, recurrence, engineName, now, nowMs)
        }

        val item = if (effectiveKind == Kind.EVENT) {
            val duration = (spec.durationMin ?: 60).toLong() * 60_000L
            Item.event(clean, start, start + duration, engineName, nowMs)
        } else {
            Item.task(clean, start, reminderAt = start.takeIf { it > 0 }, engine = engineName, now = nowMs)
        }

        var saved = repo.add(item)
        if (saved.isEvent && settings.calendarId > 0 && calendar.hasPermission()) {
            val eventId = withContext(Dispatchers.IO) { calendar.insert(saved, settings.calendarId) }
            if (eventId != null) {
                saved = saved.copy(calendarEventId = eventId)
                repo.update(saved)
            }
        }
        return Reply(describe(saved, now), engineName, saved, mutated = true)
    }

    /**
     * Repeating items are materialised: each occurrence is a real row, so
     * alarms, completion and deletion work unchanged. They stay in Morrow and
     * are deliberately not written to the system calendar (that would mean
     * guessing how a half-deleted series should look there).
     */
    private suspend fun addSeries(
        source: String,
        title: String,
        spec: TimeSpec,
        kind: Kind,
        rule: Recurrence,
        engineName: String,
        now: ZonedDateTime,
        nowMs: Long,
    ): Reply {
        val raw = spec.start?.let { Instant.ofEpochMilli(it).atZone(now.zone) }
            ?: now.toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0)).atZone(now.zone)
        // "tous les lundis à 19h" said on a Sunday must start on Monday, not tonight.
        val base = if (rule.days.isNotEmpty() && raw.dayOfWeek !in rule.days) rule.nextAfter(raw) else raw
        val times = rule.occurrences(base, SERIES_SIZE)
        val duration = (spec.durationMin ?: 60).toLong() * 60_000L
        val rrule = rule.rrule()

        val rows = times.map { at: ZonedDateTime ->
            val startMs = at.toInstant().toEpochMilli()
            if (kind == Kind.EVENT) {
                Item.event(title, startMs, startMs + duration, engineName, nowMs).copy(
                    rrule = rrule,
                    reminderAt = startMs - settings.leadMinutes * 60_000L,
                )
            } else {
                Item.task(title, startMs, startMs, engineName, nowMs).copy(rrule = rrule)
            }
        }

        val saved = repo.addSeries(rows)
        val first = saved.firstOrNull() ?: rows.first()
        val repeat = copy.repeatLabel(rrule).orEmpty()
        return Reply(
            text = copy.addedRepeating(first.title, copy.whenText(first.start, now), repeat),
            engine = engineName,
            item = first,
            mutated = true,
        )
    }

    private suspend fun complete(query: String): Reply {
        val match = findItem(query) ?: return Reply(copy.notFound(query), "rules")
        repo.setDone(match.id, true)
        return Reply(copy.markedDone(match.title), "rules", match, mutated = true)
    }

    private suspend fun move(query: String, spec: TimeSpec, now: ZonedDateTime): Reply {
        val match = findItem(query) ?: return Reply(copy.notFound(query), "rules")
        val start = spec.start ?: return Reply(copy.askWhen(match.title), "rules")
        val reminder = if (match.isEvent) start - settings.leadMinutes * 60_000L else start
        repo.move(match.id, start, reminder)
        val moved = repo.itemById(match.id) ?: match
        if (moved.isEvent && moved.calendarEventId != null) {
            withContext(Dispatchers.IO) { calendar.update(moved) }
        }
        return Reply(copy.moved(moved.title, copy.whenText(start, now)), "rules", moved, mutated = true)
    }

    private suspend fun remember(text: String): Reply {
        if (text.isBlank()) return Reply(copy.askName(), "rules")
        repo.remember(text)
        return Reply(copy.noted(text), "rules", mutated = true)
    }

    private fun describe(item: Item, now: ZonedDateTime): String = when {
        item.isEvent -> copy.addedEvent(item.title, copy.whenText(item.start, now))
        item.isAnytime -> copy.addedAnytime(item.title)
        else -> copy.addedTask(item.title, copy.whenText(item.start, now))
    }

    /**
     * Picks the item the user most likely meant. Exact titles beat prefixes, which
     * beat substrings, which beat token overlap, and a shorter title wins ties, so
     * "done with the rent" prefers "Pay the rent" over "Pay the rent and call Bob".
     */
    /** Rejects model output that drifts away from what the user said. */
    private fun acceptableTitle(candidate: String, source: String): Boolean {
        if (candidate.isBlank()) return false
        if (candidate.length > source.length * 2 + 10) return false
        val sourceWords = tokens(source)
        val candidateWords = tokens(candidate)
        if (candidateWords.isEmpty()) return false
        return candidateWords.any { word -> sourceWords.any { it == word || it.startsWith(word) || word.startsWith(it) } }
    }

    private fun findItem(query: String): Item? {
        // Only rows Morrow owns can be ticked off or moved.
        val open = repo.snapshot.value.items.filter { !it.done && it.id > 0 }
        if (open.isEmpty()) return null
        val cleaned = When.tidy(query)
        if (cleaned.isBlank()) return null
        val needle = cleaned.lowercase(Locale.US)
        val q = tokens(cleaned)

        fun score(item: Item): Double {
            val title = When.fold(item.title)
            val base = when {
                title == needle -> 1.0
                title.startsWith(needle) -> 0.9
                title.contains(needle) -> 0.8
                else -> {
                    if (q.isEmpty()) return 0.0
                    val t = tokens(item.title)
                    val hits = q.count { word -> t.any { it == word || it.startsWith(word) || word.startsWith(it) } }
                    (hits.toDouble() / q.size) * 0.7
                }
            }
            // Prefer the tightest match when several contain the same words.
            return base - (title.length - needle.length).coerceAtLeast(0) * 0.001
        }

        val best = open.maxByOrNull { score(it) } ?: return null
        return best.takeIf { score(it) >= 0.5 }
    }

    private fun tokens(text: String): List<String> = When.fold(text)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in STOPWORDS }

    companion object {
        /** How many occurrences of a repeating item are materialised up front. */
        const val SERIES_SIZE = 8

        private val ACTION_VERBS = listOf(
            "buy", "call", "send", "email", "book", "finish", "pick", "pay", "write", "clean",
            "fix", "meet", "plan", "prepare", "read", "renew", "return", "submit", "order",
            "cancel", "visit", "drop", "collect", "check", "review", "apply", "sign", "water",
            "feed", "bring", "ask", "tell", "have to", "need to", "must", "gotta", "remember",
            "meeting", "appointment", "dinner", "lunch", "flight", "class", "party",
            "acheter", "appeler", "envoyer", "payer", "finir", "prendre", "ranger", "nettoyer",
            "reparer", "ecrire", "rendez-vous", "reunion", "dejeuner", "diner", "cours", "fete",
            "anniversaire", "dois", "faut", "penser a",
        )
        private val STOPWORDS = setOf(
            "the", "and", "for", "with", "that", "this", "thing", "something", "about", "into",
            "from", "have", "need", "must", "gotta", "please", "remind", "task", "event",
            "les", "des", "une", "avec", "pour", "dans", "que", "qui", "est", "sur", "plus",
        )
    }
}
