package dev.morrow.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.morrow.MorrowApp
import kotlinx.coroutines.launch

/** Delivers a reminder, or applies the Done / Snooze action from its buttons. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
        if (id <= 0L) return
        val app = context.applicationContext as? MorrowApp ?: return
        val graph = app.graph
        val item = graph.repo.itemById(id)
        when (intent.action) {
            Reminders.ACTION_DONE -> {
                val result = goAsync()
                graph.scope.launch {
                    try {
                        graph.repo.setDone(id, true)
                        Reminders.dismiss(context, id)
                    } finally {
                        result.finish()
                    }
                }
            }
            Reminders.ACTION_SNOOZE -> {
                val result = goAsync()
                val at = System.currentTimeMillis() + 60 * 60_000L
                graph.reminders.schedule(id, at)
                Reminders.dismiss(context, id)
                result.finish()
            }
            else -> if (item != null) Reminders.show(context, item)
        }
    }
}

/** Alarms and notifications do not survive a reboot or timezone change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? MorrowApp ?: return
        app.graph.scope.launch {
            app.graph.repo.load()
            app.graph.reminders.sync(app.graph.repo.snapshot.value.items)
            Reminders.ensureChannel(context)
        }
    }
}
