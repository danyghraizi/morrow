package dev.morrow.domain

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatting shared by the UI. Day words ("Today", "Demain") come from [Copy]
 * so they follow the app language; the clock stays 24-hour.
 */
object Fmt {
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(TIME)

    fun dayLabel(epochMs: Long, now: ZonedDateTime): String =
        Copy.current().dayLabel(Instant.ofEpochMilli(epochMs).atZone(now.zone).toLocalDate(), now)

    fun whenText(epochMs: Long, now: ZonedDateTime, withTime: Boolean = true): String =
        Copy.current().whenText(epochMs, now, withTime)

    fun relative(epochMs: Long, now: ZonedDateTime): String = Copy.current().relative(epochMs, now)

    fun fullDate(now: ZonedDateTime): String =
        now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
}
