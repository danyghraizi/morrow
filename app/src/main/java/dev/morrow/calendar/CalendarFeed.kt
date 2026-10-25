package dev.morrow.calendar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.morrow.data.Item
import dev.morrow.data.Kind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** One occurrence read from the system calendar (Google, Samsung, Etar, anything). */
data class ExternalEvent(
    val eventId: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val calendarName: String,
)

/**
 * Reads the user's real calendar so Today is the whole day, not just the parts
 * Morrow created. Without this, "you are free 14:00–16:00" would happily
 * suggest a slot that Google Calendar already booked.
 *
 * Nothing is imported into the database: these rows are a live, read-only view,
 * refreshed when the app comes back to the foreground.
 */
class CalendarFeed(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _events = MutableStateFlow<List<ExternalEvent>>(emptyList())
    val events: StateFlow<List<ExternalEvent>> = _events.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun refresh(daysBack: Int = 1, daysForward: Int = 30) {
        if (!hasPermission()) {
            _events.value = emptyList()
            return
        }
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { query(daysBack, daysForward) }
            _events.value = loaded
        }
    }

    fun clear() {
        _events.value = emptyList()
    }

    private fun query(daysBack: Int, daysForward: Int): List<ExternalEvent> {
        val zone = ZoneId.systemDefault()
        val begin = Instant.now().atZone(zone).toLocalDate().minusDays(daysBack.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val end = Instant.now().atZone(zone).toLocalDate().plusDays(daysForward.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, begin)
        android.content.ContentUris.appendId(builder, end)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        return runCatching {
            // `status` is not a column of the Instances view (it lives on Events);
            // asking for it makes the whole query throw and look like "no events".
            val selection = "${CalendarContract.Instances.VISIBLE} = 1"
            context.contentResolver.query(
                builder.build(), projection, selection, null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "(no title)"
                        add(
                            ExternalEvent(
                                eventId = c.getLong(0),
                                title = title,
                                start = c.getLong(2),
                                end = c.getLong(3),
                                allDay = c.getInt(4) != 0,
                                calendarName = c.getString(5) ?: "",
                            )
                        )
                    }
                }
            } ?: emptyList()
        }.getOrElse { error ->
            // A silent empty list is how a provider/column mistake hides; say it out loud.
            android.util.Log.w("MorrowCalendar", "instances query failed: ${'$'}{error.message}")
            emptyList()
        }
    }

    companion object {
        /**
         * Local items first, then calendar occurrences that Morrow did not write
         * itself. Pure, so the merge is unit-tested without a provider.
         */
        fun merge(local: List<Item>, external: List<ExternalEvent>, include: Boolean = true): List<Item> {
            if (!include || external.isEmpty()) return local
            val linked = local.mapNotNull { it.calendarEventId }.toSet()
            val mirrored = external
                .filter { it.eventId !in linked }
                .map { it.asItem() }
            return local + mirrored
        }

        private fun ExternalEvent.asItem() = Item(
            // Negative ids mark rows that live in someone else's calendar.
            id = -eventId - 1,
            kind = Kind.EVENT,
            title = title,
            start = start,
            end = end,
            allDay = allDay,
            engine = "calendar",
            calendarEventId = eventId,
        )
    }
}
