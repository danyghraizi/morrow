package dev.morrow.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Every sentence the app composes itself: replies, agenda summaries, insights,
 * row labels. Kept as a small interface with two implementations so the domain
 * layer stays free of Android resources while still speaking both languages.
 *
 * The bundled model is English-trained, so French input is understood by the
 * rules in [When] / [QuickParse]; see README for what that means in practice.
 */
interface Copy {
    val locale: Locale

    fun dayLabel(date: LocalDate, now: ZonedDateTime): String
    fun whenText(ms: Long, now: ZonedDateTime, includeTime: Boolean = true): String
    fun relative(ms: Long, now: ZonedDateTime): String

    fun anytime(): String
    fun allDay(): String
    fun fromCalendar(): String
    fun inCalendar(): String
    fun eventLabel(): String
    fun onDevice(): String

    fun addedTask(title: String, whenText: String): String
    fun addedAnytime(title: String): String
    fun addedEvent(title: String, whenText: String): String
    fun addedRepeating(title: String, whenText: String, repeat: String): String
    fun markedDone(title: String): String
    fun notFound(query: String): String
    fun moved(title: String, whenText: String): String
    fun noted(text: String): String
    fun askName(): String
    fun askWhen(title: String): String
    fun askNameWithTime(whenText: String): String
    fun couldNotCatch(): String
    fun helpText(): String
    fun cannotRepeat(): String
    fun cannotCancel(): String

    fun agendaEmpty(): String
    fun agendaOnDay(label: String, count: Int, list: String): String
    fun agendaNothingOnDay(label: String): String
    fun agendaNothingToday(): String
    fun agendaToday(count: Int, list: String): String
    fun agendaSlipped(count: Int): String
    fun agendaAnytime(list: String): String
    fun agendaNextUp(minutes: Long, title: String): String
    fun separator(): String

    fun overdueInsight(count: Int, names: String, more: Int): String
    fun gapInsight(from: String, to: String, title: String): String
    fun emptyDayInsight(): String
    fun tomorrowFreeInsight(title: String): String
    fun nothingToSuggest(): String

    fun helloReply(): String
    fun anytimeReply(): String
    fun ackReply(): String
    fun farewellReply(): String

    fun repeatLabel(rrule: String): String?

    companion object {
        fun current(): Copy =
            if (Locale.getDefault().language == "fr") French else English
    }
}

internal abstract class BaseCopy : Copy {
    // Lazy: `locale` is provided by the subclass, which is not initialised yet here.
    private val timeFormat: DateTimeFormatter by lazy { DateTimeFormatter.ofPattern("HH:mm", locale) }

    override fun whenText(ms: Long, now: ZonedDateTime, includeTime: Boolean): String {
        if (ms <= 0L) return anytime()
        val label = dayLabel(Instant.ofEpochMilli(ms).atZone(now.zone).toLocalDate(), now)
        return if (includeTime) "$label · ${timeFormat.format(Instant.ofEpochMilli(ms).atZone(now.zone))}" else label
    }

    override fun relative(ms: Long, now: ZonedDateTime): String {
        val minutes = java.time.Duration.between(now.toInstant(), Instant.ofEpochMilli(ms)).toMinutes()
        return when {
            minutes in -59..-1 -> minutesAgo(-minutes)
            minutes == 0L -> nowLabel()
            minutes in 1..59 -> inMinutes(minutes)
            minutes in 60..(24 * 60 - 1) -> inHours(minutes / 60)
            else -> whenText(ms, now)
        }
    }

    protected abstract fun minutesAgo(minutes: Long): String
    protected abstract fun inMinutes(minutes: Long): String
    protected abstract fun inHours(hours: Long): String
    protected abstract fun nowLabel(): String

    /** Shared day bucketing; only the three words differ per language. */
    protected fun bucket(date: LocalDate, now: ZonedDateTime): String = when (date) {
        now.toLocalDate() -> todayWord()
        now.toLocalDate().plusDays(1) -> tomorrowWord()
        now.toLocalDate().minusDays(1) -> yesterdayWord()
        else -> date.format(DateTimeFormatter.ofPattern(shortDatePattern, locale))
    }

    protected abstract fun todayWord(): String
    protected abstract fun tomorrowWord(): String
    protected abstract fun yesterdayWord(): String
    protected abstract val shortDatePattern: String

    protected fun weekdayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, locale)
}

internal object English : BaseCopy() {
    override val locale: Locale = Locale.ENGLISH
    override val shortDatePattern = "EEE d MMM"

    override fun dayLabel(date: LocalDate, now: ZonedDateTime) = bucket(date, now)
    override fun todayWord() = "Today"
    override fun tomorrowWord() = "Tomorrow"
    override fun yesterdayWord() = "Yesterday"
    override fun minutesAgo(minutes: Long) = "started $minutes min ago"
    override fun inMinutes(minutes: Long) = "in $minutes min"
    override fun inHours(hours: Long) = "in $hours h"
    override fun nowLabel() = "now"

    override fun anytime() = "Anytime"
    override fun allDay() = "All day"
    override fun fromCalendar() = "from calendar"
    override fun inCalendar() = "in calendar"
    override fun eventLabel() = "event"
    override fun onDevice() = "on-device"

    override fun addedTask(title: String, whenText: String) = "\"$title\": $whenText. I will nudge you."
    override fun addedAnytime(title: String) = "\"$title\" is in your anytime list."
    override fun addedEvent(title: String, whenText: String) = "Added \"$title\" for $whenText."
    override fun addedRepeating(title: String, whenText: String, repeat: String) =
        "\"$title\" starts $whenText and repeats $repeat."
    override fun markedDone(title: String) = "\"$title\" is done."
    override fun notFound(query: String) = "I could not find anything like \"$query\" on your list."
    override fun moved(title: String, whenText: String) = "Moved \"$title\" to $whenText."
    override fun noted(text: String) = "Noted: $text"
    override fun askName() = "What should I call it?"
    override fun askWhen(title: String) = "When should \"$title\" move to?"
    override fun askNameWithTime(whenText: String) = "What should I call the thing at $whenText?"
    override fun couldNotCatch() =
        "I did not catch that. Try \"dentist friday at 3pm\" or \"remind me to pay the rent\"."

    override fun helpText() = """
        I keep your day on this phone, offline.
        • "pay the rent friday" adds a task and nudges you
        • "dentist tuesday at 3" books a calendar event
        • "gym every monday at 7" repeats it weekly
        • "done with the rent" ticks it off
        • "move the dentist to thursday" reschedules
        • "what's on my plate" today at a glance
        • "remember I hate mornings" keeps a note
    """.trimIndent()

    override fun cannotRepeat() =
        "I can repeat daily, on chosen weekdays, or monthly. That pattern was saved as a one-off."

    override fun cannotCancel() =
        "I cannot cancel or delete yet. Open the item in Today and use Delete there."

    override fun agendaEmpty() = "Today is clear. Tell me what you need to do and I will hold on to it."
    override fun agendaOnDay(label: String, count: Int, list: String) =
        "$label: $count ${if (count == 1) "thing" else "things"}\n$list"

    override fun agendaNothingOnDay(label: String) = "$label: nothing scheduled."
    override fun agendaNothingToday() = "Nothing scheduled today"
    override fun agendaToday(count: Int, list: String) = "$count today: $list"
    override fun agendaSlipped(count: Int) =
        "\n$count slipped past. Say \"move it to today\" to pull them in."
    override fun agendaAnytime(list: String) = "\nAnytime: $list"
    override fun agendaNextUp(minutes: Long, title: String) = "\nNext up in $minutes min: $title."
    override fun separator() = " · "

    override fun overdueInsight(count: Int, names: String, more: Int): String {
        val extra = if (more > 0) " and $more more" else ""
        return "$count slipped past: $names$extra. Move them to today?"
    }

    override fun gapInsight(from: String, to: String, title: String) =
        "$from–$to is free. Start \"$title\" then?"

    override fun emptyDayInsight() = "Nothing booked today. Tell me one thing you keep putting off."

    override fun tomorrowFreeInsight(title: String) =
        "Today is stacked and tomorrow is clear. Move \"$title\" to tomorrow?"

    override fun nothingToSuggest() =
        "Nothing urgent. Pick the smallest thing on your list and do it for ten minutes."

    override fun helloReply() = "Hello. What is on your mind?"
    override fun anytimeReply() = "Anytime."
    override fun ackReply() = "Right. Anything else?"
    override fun farewellReply() = "See you."

    override fun repeatLabel(rrule: String): String? {
        val rule = rrule.uppercase(Locale.US)
        val interval = Regex("INTERVAL=(\\d+)").find(rule)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val every = if (interval == 1) "every" else "every $interval"
        return when {
            rule.contains("FREQ=DAILY") -> "$every day"
            rule.contains("BYDAY=MO,TU,WE,TH,FR") -> "$every weekday"
            rule.contains("FREQ=WEEKLY;BYDAY=") -> {
                val days = Regex("BYDAY=([A-Z,]+)").find(rule)?.groupValues?.get(1)
                    ?.split(',')?.mapNotNull { code -> DAY_CODES[code] } ?: emptyList()
                "$every ${days.joinToString(" and ") { weekdayName(it) }}"
            }
            rule.contains("FREQ=WEEKLY") -> if (interval == 1) "every week" else "every $interval weeks"
            rule.contains("FREQ=MONTHLY") -> "every month"
            else -> null
        }
    }

    private val DAY_CODES = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY,
        "SU" to DayOfWeek.SUNDAY,
    )
}

internal object French : BaseCopy() {
    override val locale: Locale = Locale.FRENCH
    override val shortDatePattern = "EEE d MMM"

    override fun dayLabel(date: LocalDate, now: ZonedDateTime) = bucket(date, now)
    override fun todayWord() = "Aujourd'hui"
    override fun tomorrowWord() = "Demain"
    override fun yesterdayWord() = "Hier"
    override fun minutesAgo(minutes: Long) = "commencé il y a $minutes min"
    override fun inMinutes(minutes: Long) = "dans $minutes min"
    override fun inHours(hours: Long) = "dans $hours h"
    override fun nowLabel() = "maintenant"

    override fun anytime() = "Quand je veux"
    override fun allDay() = "Toute la journée"
    override fun fromCalendar() = "depuis l'agenda"
    override fun inCalendar() = "dans l'agenda"
    override fun eventLabel() = "rendez-vous"
    override fun onDevice() = "sur l'appareil"

    override fun addedTask(title: String, whenText: String) = "« $title » : $whenText. Je vous préviens."
    override fun addedAnytime(title: String) = "« $title » est dans votre liste, sans date."
    override fun addedEvent(title: String, whenText: String) = "« $title » ajouté pour $whenText."
    override fun addedRepeating(title: String, whenText: String, repeat: String) =
        "« $title » commence $whenText et se répète $repeat."
    override fun markedDone(title: String) = "« $title » est fait."
    override fun notFound(query: String) = "Je ne trouve rien comme « $query » dans votre liste."
    override fun moved(title: String, whenText: String) = "« $title » déplacé à $whenText."
    override fun noted(text: String) = "Noté : $text"
    override fun askName() = "Comment faut-il l'appeler ?"
    override fun askWhen(title: String) = "Pour quand faut-il déplacer « $title » ?"
    override fun askNameWithTime(whenText: String) = "Comment appeler ce qui est prévu $whenText ?"
    override fun couldNotCatch() =
        "Je n'ai pas compris. Essayez « dentiste vendredi à 15h » ou « rappelle-moi de payer le loyer »."

    override fun helpText() = """
        Je garde votre journée sur ce téléphone, hors ligne.
        • « payer le loyer vendredi » ajoute une tâche et vous prévient
        • « dentiste mardi à 15h » crée un rendez-vous dans l'agenda
        • « yoga tous les lundis à 19h » répète chaque semaine
        • « le loyer est fait » coche la tâche
        • « déplace le dentiste à jeudi » reporte
        • « qu'est-ce que j'ai aujourd'hui » votre journée
        • « retiens que je préfère le matin » garde une note
    """.trimIndent()

    override fun cannotRepeat() =
        "Je sais répéter chaque jour, certains jours de la semaine, ou chaque mois. Ce motif a été enregistré une seule fois."

    override fun cannotCancel() =
        "Je ne peux pas encore annuler ni supprimer. Ouvrez l'élément dans Aujourd'hui et utilisez Supprimer."

    override fun agendaEmpty() =
        "Rien de prévu aujourd'hui. Dites-moi ce que vous avez à faire et je le garde."
    override fun agendaOnDay(label: String, count: Int, list: String) =
        "$label : $count ${if (count == 1) "chose" else "choses"}\n$list"

    override fun agendaNothingOnDay(label: String) = "$label : rien de prévu."
    override fun agendaNothingToday() = "Rien de prévu aujourd'hui"
    override fun agendaToday(count: Int, list: String) = "$count aujourd'hui : $list"
    override fun agendaSlipped(count: Int) =
        "\n$count en retard. Dites « déplace-les à aujourd'hui » pour les ramener."
    override fun agendaAnytime(list: String) = "\nSans date : $list"
    override fun agendaNextUp(minutes: Long, title: String) = "\nProchain dans $minutes min : $title."
    override fun separator() = " · "

    override fun overdueInsight(count: Int, names: String, more: Int): String {
        val extra = if (more > 0) " et $more autres" else ""
        return "$count en retard : $names$extra. Les déplacer à aujourd'hui ?"
    }

    override fun gapInsight(from: String, to: String, title: String) =
        "$from–$to est libre. Commencer « $title » ?"

    override fun emptyDayInsight() =
        "Rien de prévu aujourd'hui. Dites-moi une chose que vous repoussez."

    override fun tomorrowFreeInsight(title: String) =
        "Aujourd'hui est chargé et demain est libre. Déplacer « $title » à demain ?"

    override fun nothingToSuggest() =
        "Rien d'urgent. Prenez la plus petite chose de votre liste et faites-la dix minutes."

    override fun helloReply() = "Bonjour. Qu'est-ce qui vous occupe ?"
    override fun anytimeReply() = "Je vous en prie."
    override fun ackReply() = "Très bien. Autre chose ?"
    override fun farewellReply() = "À bientôt."

    override fun repeatLabel(rrule: String): String? {
        val rule = rrule.uppercase(Locale.US)
        val interval = Regex("INTERVAL=(\\d+)").find(rule)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return when {
            rule.contains("FREQ=DAILY") -> if (interval == 1) "tous les jours" else "tous les $interval jours"
            rule.contains("BYDAY=MO,TU,WE,TH,FR") -> "en semaine"
            rule.contains("FREQ=WEEKLY;BYDAY=") -> {
                val days = Regex("BYDAY=([A-Z,]+)").find(rule)?.groupValues?.get(1)
                    ?.split(',')?.mapNotNull { code -> DAY_CODES[code] } ?: emptyList()
                val names = days.joinToString(" et ") { day ->
                    when (day) {
                        DayOfWeek.MONDAY -> "lundis"
                        DayOfWeek.TUESDAY -> "mardis"
                        DayOfWeek.WEDNESDAY -> "mercredis"
                        DayOfWeek.THURSDAY -> "jeudis"
                        DayOfWeek.FRIDAY -> "vendredis"
                        DayOfWeek.SATURDAY -> "samedis"
                        DayOfWeek.SUNDAY -> "dimanches"
                    }
                }
                when {
                    interval != 1 -> "toutes les $interval semaines, le $names"
                    days.size == 1 -> "tous les $names"
                    else -> "les $names"
                }
            }
            rule.contains("FREQ=WEEKLY") ->
                if (interval == 1) "toutes les semaines" else "toutes les $interval semaines"
            rule.contains("FREQ=MONTHLY") -> "tous les mois"
            else -> null
        }
    }

    private val DAY_CODES = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY,
        "SU" to DayOfWeek.SUNDAY,
    )
}
