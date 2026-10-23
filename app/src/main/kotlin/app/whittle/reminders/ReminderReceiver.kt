package app.whittle.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.whittle.WhittleApplication
import kotlinx.coroutines.launch

/**
 * Exact alarms that fire in Doze. Whittle targets SDK 28, so they need no exact-alarm permission. Each reminder's alarm is
 * told apart by its address, since Android doesn't count extras when it matches them.
 */
class AndroidReminderAlarms(private val context: Context) : ReminderAlarms {
    private val manager = context.getSystemService(AlarmManager::class.java)

    override fun set(id: String, atMillis: Long) {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(id))
    }

    override fun cancel(id: String) {
        manager.cancel(pending(id))
    }

    private fun pending(id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_REMIND)
            .setData(Uri.Builder().scheme(SCHEME).authority(id).build())
            .putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST = 0
        const val SCHEME = "whittle-reminder"
    }
}

/** Shows a reminder when its alarm goes off. Not exported: only Whittle's own alarms reach it. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val app = context.applicationContext as WhittleApplication
        val pending = goAsync()
        app.graph.appScope.launch {
            try {
                app.graph.reminders.due(id)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "app.whittle.action.REMIND"
        const val EXTRA_ID = "reminder"
    }
}
