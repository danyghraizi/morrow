package dev.morrow.data

/** A single thing on the timeline: a to-do or a calendar event. */
data class Item(
    val id: Long = 0L,
    val kind: Kind = Kind.TASK,
    val title: String = "",
    /** Due time for a task, start time for an event. 0 means "anytime". */
    val start: Long = 0L,
    /** End time for events, null otherwise. */
    val end: Long? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    /** For events mirrored into a system calendar. */
    val calendarEventId: Long? = null,
    /** Only set on rows read back from the system calendar; never persisted. */
    val allDay: Boolean = false,
    /** iCalendar rule when this row repeats ("FREQ=WEEKLY;BYDAY=MO"). */
    val rrule: String? = null,
    /** Groups the materialised occurrences of one repeating item. */
    val seriesId: Long? = null,
    val reminderAt: Long? = null,
    val engine: String = "manual",
    val createdAt: Long = 0L,
) {
    val isEvent: Boolean get() = kind == Kind.EVENT
    val isAnytime: Boolean get() = start == 0L

    companion object {
        fun task(title: String, start: Long, reminderAt: Long?, engine: String, now: Long) = Item(
            kind = Kind.TASK, title = title, start = start, reminderAt = reminderAt,
            engine = engine, createdAt = now,
        )

        fun event(title: String, start: Long, end: Long, engine: String, now: Long) = Item(
            kind = Kind.EVENT, title = title, start = start, end = end,
            engine = engine, createdAt = now,
        )
    }
}

enum class Kind { TASK, EVENT }

/** A lasting fact the user asked us to keep. */
data class Memory(val id: Long = 0L, val text: String, val createdAt: Long = 0L)

/** One exchange in the Ask tab, persisted so it survives restarts. */
data class Turn(
    val id: Long = 0L,
    val user: String,
    val reply: String,
    val engine: String,
    val at: Long,
)

data class Snapshot(
    val items: List<Item> = emptyList(),
    val memories: List<Memory> = emptyList(),
    val turns: List<Turn> = emptyList(),
)
