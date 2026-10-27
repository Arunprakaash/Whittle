package app.whittle.automations.events

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.whittle.WhittleApplication
import app.whittle.automations.EventKind
import app.whittle.automations.PhoneEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Sees every notification once the user allows it in Android's settings, and hands the ones an automation may run on
 * to [EventDispatch]. A notification from an app nobody watches is dropped before its text is read, and while no
 * automation watches any app the listener unbinds itself ([EventSources] binds it again when one does). Nothing is
 * stored here but each notification's last text, in memory, to tell a re-post from a new one.
 */
class WhittleNotificationListener : NotificationListenerService() {
    private val lastTextByKey = LinkedHashMap<String, String>()

    /** The packages that may start an automation; null until the saved automations have loaded (a fresh process). */
    @Volatile
    private var watched: Set<String>? = null
    private var watching: Job? = null

    override fun onListenerConnected() {
        val app = application as WhittleApplication
        watching?.cancel()
        watching = app.graph.appScope.launch {
            app.eventSources.notificationApps.filterNotNull().collect { apps ->
                watched = apps
                if (apps.isEmpty()) requestUnbind()
            }
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
                val packages = app.eventSources.notificationApps.filterNotNull().first()
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
                maxKeys = (application as WhittleApplication).graph.automations.config.events.maxNotificationKeys,
            )
        }
        return text.takeIf { fire }
    }

    private suspend fun send(app: WhittleApplication, sbn: StatusBarNotification, text: String) {
        app.eventDispatch.dispatch(PhoneEvent(EventKind.NOTIFICATION, sbn.packageName, text, app.graph.clock.nowMillis()))
    }
}
