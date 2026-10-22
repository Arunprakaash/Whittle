package app.whittle.automations

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.whittle.WhittleApplication
import kotlinx.coroutines.launch

/**
 * Wakes for a due automation, and sets every alarm again after the phone restarts, the app updates or the clock
 * changes. Not exported: only Whittle's own alarms and the system's broadcasts reach it.
 */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as WhittleApplication
        val pending = goAsync()
        app.graph.appScope.launch {
            try {
                if (intent.action == ACTION_RUN) {
                    val id = intent.getStringExtra(EXTRA_ID) ?: return@launch
                    app.automationRunner.due(id, intent.getLongExtra(EXTRA_SCHEDULED_FOR, 0))
                } else {
                    app.graph.automations.rescheduleAll()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RUN = "app.whittle.action.RUN_AUTOMATION"
        const val EXTRA_ID = "automation"
        const val EXTRA_SCHEDULED_FOR = "scheduledFor"
    }
}
