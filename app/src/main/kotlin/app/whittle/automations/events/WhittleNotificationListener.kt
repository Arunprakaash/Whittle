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
 * watches to [EventDispatch]. With none watching it ignores everything after one check. Nothing is stored here but each
 * notification's last text, in memory, to tell a re-post from a new one.
 */
class WhittleNotificationListener : NotificationListenerService() {
    private val lastTextByKey = LinkedHashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as WhittleApplication
        val text = textOf(sbn)
        val ongoing = sbn.isOngoing
        val summary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        app.graph.appScope.launch {
            // Awaits the saved automations: in a fresh process they are not loaded yet, and none would mean a dropped event.
            val watched = app.graph.automations.loadedAll()
                .filter { it.enabled && it.isEvent }
                .mapNotNull { (it.trigger as? Trigger.Notification)?.app }
                .toSet()
            if (sbn.packageName !in watched) return@launch
            val fire = synchronized(lastTextByKey) {
                NotificationFilter.shouldFire(
                    sbn.packageName, packageName, ongoing, summary, sbn.key, text, lastTextByKey,
                )
            }
            if (fire) {
                app.eventDispatch.dispatch(PhoneEvent(EventKind.NOTIFICATION, sbn.packageName, text, app.graph.clock.nowMillis()))
            }
        }
    }

    private fun textOf(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        return if (title.isEmpty()) body else "$title\n$body"
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lastTextByKey) { lastTextByKey.remove(sbn.key) }
    }
}
