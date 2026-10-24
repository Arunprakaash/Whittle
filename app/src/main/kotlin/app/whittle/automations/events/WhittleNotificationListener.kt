package app.whittle.automations.events

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.whittle.WhittleApplication
import app.whittle.automations.Automation
import app.whittle.automations.EventKind
import app.whittle.automations.PhoneEvent
import app.whittle.automations.Trigger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Sees every notification once the user allows it in Android's settings, and hands the ones an enabled automation
 * watches to [EventDispatch]. A notification from an app nobody watches is dropped before its text is read. Nothing is
 * stored here but each notification's last text, in memory, to tell a re-post from a new one.
 */
class WhittleNotificationListener : NotificationListenerService() {
    private val lastTextByKey = LinkedHashMap<String, String>()

    /** The packages enabled automations watch; null until the saved automations have loaded (a fresh process). */
    @Volatile
    private var watched: Set<String>? = null
    private var watching: Job? = null

    override fun onListenerConnected() {
        val automations = (application as WhittleApplication).graph.automations
        watching?.cancel()
        watching = (application as WhittleApplication).graph.appScope.launch {
            automations.loadedAll()
            automations.all.collect { watched = watchedPackages(it) }
        }
    }

    override fun onListenerDisconnected() {
        watching?.cancel()
        watched = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as WhittleApplication
        val known = watched
        if (known == null) {
            // Not loaded yet: wait for the automations rather than drop the notification.
            app.graph.appScope.launch {
                val packages = watchedPackages(app.graph.automations.loadedAll())
                if (sbn.packageName in packages) textIfNew(sbn)?.let { send(app, sbn, it) }
            }
            return
        }
        if (sbn.packageName !in known) return
        // Filtered here, on the posting thread, so two posts with one key are judged in the order they came.
        textIfNew(sbn)?.let { text -> app.graph.appScope.launch { send(app, sbn, text) } }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(lastTextByKey) { lastTextByKey.remove(sbn.key) }
    }

    /** The notification's text when it counts as a new event, else null. */
    private fun textIfNew(sbn: StatusBarNotification): String? {
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
        return text.takeIf { fire }
    }

    private suspend fun send(app: WhittleApplication, sbn: StatusBarNotification, text: String) {
        app.eventDispatch.dispatch(PhoneEvent(EventKind.NOTIFICATION, sbn.packageName, text, app.graph.clock.nowMillis()))
    }

    private fun watchedPackages(all: List<Automation>): Set<String> =
        all.filter { it.enabled && it.isEvent }.mapNotNull { (it.trigger as? Trigger.Notification)?.app }.toSet()
}
