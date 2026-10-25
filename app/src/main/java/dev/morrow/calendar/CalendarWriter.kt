package dev.morrow.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dev.morrow.data.Item
import java.time.ZoneId

data class CalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

/**
 * Writes events into whichever system calendar the user picked, so they show up
 * in Google Calendar, Etar, Samsung Calendar; anything that reads the provider.
 * It never creates its own account and never touches events it did not write.
 */
class CalendarWriter(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        return context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, selection, null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(CalendarInfo(c.getLong(0), c.getString(1) ?: "Calendar", c.getString(2) ?: "", c.getInt(3)))
                }
            }
        } ?: emptyList()
    }

    fun insert(item: Item, calendarId: Long): Long? {
        if (!hasPermission() || calendarId <= 0) return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, item.title)
            put(CalendarContract.Events.DTSTART, item.start)
            put(CalendarContract.Events.DTEND, item.end ?: (item.start + 3_600_000L))
            put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(CalendarContract.Events.HAS_ALARM, 0)
        }
        return runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull()
        }.getOrNull()
    }

    fun update(item: Item): Boolean {
        val eventId = item.calendarEventId ?: return false
        if (!hasPermission()) return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, item.title)
            put(CalendarContract.Events.DTSTART, item.start)
            put(CalendarContract.Events.DTEND, item.end ?: (item.start + 3_600_000L))
        }
        return runCatching {
            context.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), values, null, null,
            ) > 0
        }.getOrDefault(false)
    }

    fun delete(eventId: Long): Boolean = runCatching {
        context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null,
        ) > 0
    }.getOrDefault(false)
}
