package dev.morrow.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.morrow.MainActivity
import dev.morrow.R
import dev.morrow.data.Item
import dev.morrow.data.Kind
import dev.morrow.data.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One alarm per upcoming item, delivered by AlarmManager. No foreground service,
 * no wake locks, no polling: the OS wakes the app exactly when it is needed and
 * the app goes back to sleep.
 */
class Reminders(private val context: Context, private val settings: Settings) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun canScheduleExact(): Boolean {
        val am = alarmManager ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** Rebuilds the alarm set so it always matches the database. */
    fun sync(items: List<Item>) {
        val now = System.currentTimeMillis()
        val wanted = items.filter { !it.done && it.start > now }
            .mapNotNull { item -> reminderTime(item)?.takeIf { it > now }?.let { item.id to it } }
            .toMap()

        settings.scheduledIds.filterNot { it in wanted.keys }.forEach { cancel(it) }

        wanted.forEach { (id, at) -> schedule(id, at) }
        settings.scheduledIds = wanted.keys
    }

    fun reminderTime(item: Item): Long? = when {
        item.start <= 0L -> null
        item.kind == Kind.EVENT -> item.start - settings.leadMinutes * 60_000L
        else -> item.start
    }

    fun schedule(id: Long, at: Long) {
        val am = alarmManager ?: return
        val pending = pendingIntent(id, at)
        val exact = canScheduleExact()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(id: Long) {
        alarmManager?.cancel(pendingIntent(id, 0L))
    }

    private fun pendingIntent(id: Long, at: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .setData(Uri.parse("morrow://item/$id"))
            .putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val ACTION_REMIND = "dev.morrow.REMIND"
        const val ACTION_DONE = "dev.morrow.DONE"
        const val ACTION_SNOOZE = "dev.morrow.SNOOZE"
        const val EXTRA_ID = "item_id"
        private const val CHANNEL = "reminders"

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }

        fun show(context: Context, item: Item) {
            // On API 33+ the user can refuse notifications; nothing to do but stay quiet.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) return

            val zone = ZoneId.systemDefault()
            val whenText = if (item.start == 0L) "Anytime" else {
                val t = Instant.ofEpochMilli(item.start).atZone(zone)
                t.format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.US))
            }
            val open = PendingIntent.getActivity(
                context, item.id.toInt(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            fun action(action: String, label: String, request: Int): PendingIntent =
                PendingIntent.getBroadcast(
                    context, request,
                    Intent(context, ReminderReceiver::class.java)
                        .setAction(action)
                        .setData(Uri.parse("morrow://item/${item.id}/$action"))
                        .putExtra(EXTRA_ID, item.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )

            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(item.title)
                .setContentText(whenText)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addAction(0, context.getString(R.string.notif_done), action(ACTION_DONE, "Done", item.id.toInt() * 10))
                .addAction(0, context.getString(R.string.notif_snooze), action(ACTION_SNOOZE, "Snooze", item.id.toInt() * 10 + 1))
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(item.id.toInt(), notification) }
        }

        fun dismiss(context: Context, id: Long) {
            NotificationManagerCompat.from(context).cancel(id.toInt())
        }
    }
}
