package app.whittle.automations.events

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.whittle.WhittleApplication
import app.whittle.automations.EventKind
import app.whittle.automations.PhoneEvent
import app.whittle.automations.Trigger
import kotlinx.coroutines.launch

/**
 * Sees every notification once the user allows it in Android's settings, and hands the ones an enabled automation
 * watches to [EventDispatch]. With none watching it ignores everything at once. Nothing is stored here but each
 * notification's last text, in memory, to tell a re-post from a new one.
 */
class WhittleNotificationListener : NotificationListenerService() {
    private val lastTextByKey = LinkedHashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as WhittleApplication
        val watched = app.graph.automations.all.value
            .filter { it.enabled && it.isEvent }
            .mapNotNull { (it.trigger as? Trigger.Notification)?.app }
            .toSet()
        if (sbn.packageName !in watched) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val text = if (title.isEmpty()) body else "$title\n$body"
        val fire = synchronized(lastTextByKey) {
            NotificationFilter.shouldFire(
                sbn.packageName, packageName,
                ongoing = sbn.isOngoing,
                groupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                key = sbn.key, text = text, lastTextByKey = lastTextByKey,
            )
        }
        if (!fire) return
        app.graph.appScope.launch {
            app.eventDispatch.dispatch(PhoneEvent(EventKind.NOTIFICATION, sbn.packageName, text, app.graph.clock.nowMillis()))
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lastTextByKey) { lastTextByKey.remove(sbn.key) }
    }
}
