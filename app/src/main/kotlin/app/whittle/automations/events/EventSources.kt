package app.whittle.automations.events

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import app.whittle.automations.Automation
import app.whittle.automations.Automations
import app.whittle.automations.Trigger
import app.whittle.automations.capabilities
import app.whittle.phone.PhoneActionsConfig
import app.whittle.settings.UserSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the event sources must do for the triggers that may run: listen for texts, and which apps' notifications to read. */
data class SourceNeeds(val texts: Boolean = false, val notificationApps: Set<String> = emptySet(), val phone: PhoneNeeds = PhoneNeeds())

/** The enabled event automations' triggers that [allows] lets run. */
fun allowedTriggers(all: List<Automation>, allows: (Trigger) -> Boolean): List<Trigger> =
    all.filter { it.enabled && it.isEvent }.mapNotNull { it.trigger }.filter(allows)

fun sourceNeeds(triggers: List<Trigger>) = SourceNeeds(
    texts = triggers.any { it is Trigger.Sms },
    notificationApps = triggers.filterIsInstance<Trigger.Notification>().map { it.app }.toSet(),
    phone = needsOf(triggers),
)

/**
 * Whether [trigger] may run: everything it needs ([byType], automations.json's events.capabilities) is [granted], and no
 * switch in Settings › On this phone that covers one of them is in [off].
 */
fun triggerAllowed(
    trigger: Trigger,
    byType: Map<String, List<String>>,
    switches: List<PhoneActionsConfig.Switch>,
    off: Set<String>,
    granted: (String) -> Boolean,
): Boolean = trigger.capabilities(byType).all { capabilityAllowed(it, switches, off, granted) }

/** Whether [capability] is [granted] and no switch that covers it is in [off]. */
fun capabilityAllowed(capability: String, switches: List<PhoneActionsConfig.Switch>, off: Set<String>, granted: (String) -> Boolean): Boolean =
    granted(capability) && switches.none { it.id in off && capability in it.capabilities }

/**
 * Turns the event sources on and off with the triggers that need them, so nothing listens for nothing: the text receiver
 * is enabled only while a text automation may run (Android starts no process for a text otherwise), the notification
 * listener stays bound only while one watches an app, and charging, Wi-Fi and places are armed through [watch]. Follows
 * the automations, the switches in Settings and, through [recheck], what the user has granted since.
 */
class EventSources(
    private val context: Context,
    private val automations: Automations,
    private val settings: StateFlow<UserSettings?>,
    private val switches: List<PhoneActionsConfig.Switch>,
    private val granted: (String) -> Boolean,
    private val watch: () -> PhoneStateWatch,
) {
    private val _checks = MutableStateFlow(0)

    /** Counts [recheck]s, for whatever shows what's granted. */
    val checks: StateFlow<Int> = _checks.asStateFlow()
    private val _notificationApps = MutableStateFlow<Set<String>?>(null)

    /** The apps whose notifications may start an automation now; null until the saved automations have loaded. */
    val notificationApps: StateFlow<Set<String>?> = _notificationApps.asStateFlow()

    /** Whether [trigger] may run with what's granted and switched on now. */
    fun allows(trigger: Trigger): Boolean =
        triggerAllowed(trigger, automations.config.events.capabilities, switches, settings.value?.phoneOff.orEmpty(), granted)

    /** Reads what's granted again: after a permission prompt, and when the user comes back from Android's settings. */
    fun recheck() = _checks.update { it + 1 }

    fun start(scope: CoroutineScope): Job = scope.launch {
        automations.loadedAll()
        combine(automations.all, settings, checks) { all, _, _ -> sourceNeeds(allowedTriggers(all, ::allows)) }
            .distinctUntilChanged()
            .collect { apply(it) }
    }

    private suspend fun apply(needs: SourceNeeds) {
        setEnabled(SmsReceiver::class.java, needs.texts)
        val watchedBefore = _notificationApps.value.orEmpty()
        _notificationApps.value = needs.notificationApps
        // A listener that unbound itself for want of apps is bound again once one is watched (only an allowed one is).
        if (watchedBefore.isEmpty() && needs.notificationApps.isNotEmpty()) {
            try {
                NotificationListenerService.requestRebind(ComponentName(context, WhittleNotificationListener::class.java))
            } catch (_: SecurityException) {
                // Access was taken away since it was read; the next recheck finds it gone.
            }
        }
        watch().arm()
    }

    private fun setEnabled(component: Class<*>, on: Boolean) {
        val name = ComponentName(context, component)
        val wanted = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val packages = context.packageManager
        if (packages.getComponentEnabledSetting(name) != wanted) packages.setComponentEnabledSetting(name, wanted, PackageManager.DONT_KILL_APP)
    }
}
