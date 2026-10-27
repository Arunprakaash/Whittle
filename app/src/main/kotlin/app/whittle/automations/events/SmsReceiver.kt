package app.whittle.automations.events

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import app.whittle.WhittleApplication
import app.whittle.automations.EventKind
import app.whittle.automations.PhoneEvent
import kotlinx.coroutines.launch

/**
 * Wakes for each text the phone receives, even when Whittle isn't running, and hands it to [EventDispatch]. Only the
 * system may send it (BROADCAST_SMS). The text is not kept here; the gate masks and queues what a match needs.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty().map {
            (it.displayOriginatingAddress ?: "") to (it.messageBody ?: "")
        }
        if (parts.isEmpty()) return
        val app = context.applicationContext as WhittleApplication
        val pending = goAsync()
        app.graph.appScope.launch {
            try {
                val now = app.graph.clock.nowMillis()
                joinParts(parts).forEach { (sender, text) ->
                    app.eventDispatch.dispatch(PhoneEvent(EventKind.SMS, sender, text, now))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
