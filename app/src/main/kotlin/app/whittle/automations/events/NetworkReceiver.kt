package app.whittle.automations.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.whittle.WhittleApplication
import kotlinx.coroutines.launch

/** Wakes when a Wi-Fi network comes or goes (from the callback armed in [AndroidPhoneScheduler]) and looks at the phone. Not exported. */
class NetworkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as WhittleApplication
        val pending = goAsync()
        app.graph.appScope.launch {
            try {
                app.phoneStateWatch.check()
            } finally {
                pending.finish()
            }
        }
    }
}
