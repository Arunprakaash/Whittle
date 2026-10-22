package app.whittle.automations

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Exact alarms that fire in Doze. Whittle targets SDK 28, so they need no exact-alarm permission, and the receiver
 * they wake may start the run's foreground service from the background.
 */
class AndroidAutomationAlarms(private val context: Context) : AutomationAlarms {
    private val manager = context.getSystemService(AlarmManager::class.java)

    override fun set(id: String, atMillis: Long, scheduledForMillis: Long, retry: Boolean) {
        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending(id, retry, scheduledForMillis))
    }

    override fun cancel(id: String) {
        manager.cancel(pending(id, retry = false, scheduledForMillis = 0))
        manager.cancel(pending(id, retry = true, scheduledForMillis = 0))
    }

    /** The address tells the two alarms of one automation apart; extras don't count when Android matches them. */
    private fun pending(id: String, retry: Boolean, scheduledForMillis: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST,
        Intent(context, AutomationReceiver::class.java)
            .setAction(AutomationReceiver.ACTION_RUN)
            .setData(Uri.Builder().scheme(SCHEME).authority(id).appendPath(if (retry) RETRY else NEXT).build())
            .putExtra(AutomationReceiver.EXTRA_ID, id)
            .putExtra(AutomationReceiver.EXTRA_SCHEDULED_FOR, scheduledForMillis),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val REQUEST = 0
        const val SCHEME = "whittle-automation"
        const val NEXT = "next"
        const val RETRY = "retry"
    }
}
