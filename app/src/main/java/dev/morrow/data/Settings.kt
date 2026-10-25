package dev.morrow.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small preference bag with an observable snapshot. No DataStore for five values. */
class Settings(context: Context) {

    data class Values(
        val onboarded: Boolean = false,
        val calendarId: Long = -1L,
        val leadMinutes: Int = 10,
        val useModel: Boolean = true,
        val showCalendar: Boolean = true,
    )

    private val prefs = context.getSharedPreferences("morrow.settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Values> = _state.asStateFlow()

    private fun read() = Values(
        onboarded = prefs.getBoolean("onboarded", false),
        calendarId = prefs.getLong("calendarId", -1L),
        leadMinutes = prefs.getInt("leadMinutes", 10),
        useModel = prefs.getBoolean("useModel", true),
        showCalendar = prefs.getBoolean("showCalendar", true),
    )

    var onboarded: Boolean
        get() = _state.value.onboarded
        set(value) {
            prefs.edit().putBoolean("onboarded", value).apply()
            _state.value = _state.value.copy(onboarded = value)
        }

    /** Target calendar for events, or -1 for "local only". */
    var calendarId: Long
        get() = _state.value.calendarId
        set(value) {
            prefs.edit().putLong("calendarId", value).apply()
            _state.value = _state.value.copy(calendarId = value)
        }

    /** Minutes before an event to nudge. Tasks nudge at their due minute. */
    var leadMinutes: Int
        get() = _state.value.leadMinutes
        set(value) {
            prefs.edit().putInt("leadMinutes", value).apply()
            _state.value = _state.value.copy(leadMinutes = value)
        }

    /** The LLM can be switched off entirely; the rules keep working. */
    var useModel: Boolean
        get() = _state.value.useModel
        set(value) {
            prefs.edit().putBoolean("useModel", value).apply()
            _state.value = _state.value.copy(useModel = value)
        }

    /** Mirror existing system-calendar events into Today. */
    var showCalendar: Boolean
        get() = _state.value.showCalendar
        set(value) {
            prefs.edit().putBoolean("showCalendar", value).apply()
            _state.value = _state.value.copy(showCalendar = value)
        }

    /** Item ids with a scheduled alarm, so stale alarms can be cancelled. */
    var scheduledIds: Set<Long>
        get() = prefs.getStringSet("scheduled", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet("scheduled", value.map { it.toString() }.toSet()).apply()
}
