package app.whittle.automations.events

import app.whittle.automations.Automations
import app.whittle.automations.EventGate
import app.whittle.automations.GateDecision
import app.whittle.automations.PhoneEvent
import app.whittle.automations.Trigger
import app.whittle.automations.matches

/**
 * Where every event source hands over what it saw: offers it to each enabled automation whose trigger matches, and
 * tells the runner about the ones the gate accepted. [eventsArrived] is the runner's, passed as a function so the
 * dispatcher needs no Android types.
 */
class EventDispatch(
    private val automations: Automations,
    private val gate: EventGate,
    /** Whether a trigger may run: what it needs is granted and its switch in Settings is on. */
    private val allows: (Trigger) -> Boolean,
    private val eventsArrived: suspend (String) -> Unit,
) {
    suspend fun dispatch(event: PhoneEvent) {
        for (automation in automations.loadedAll()) {
            val trigger = automation.trigger
            if (!automation.enabled || !automation.isEvent || trigger == null || !allows(trigger)) continue
            if (!trigger.matches(event, automations.config.events.senderSuffixDigits)) continue
            // The trigger knows the app by the name the user picked it by; the event only has its package.
            val named = (trigger as? Trigger.Notification)?.let { event.copy(sourceLabel = it.appLabel) } ?: event
            if (gate.offer(automation.id, named) is GateDecision.RunAt) eventsArrived(automation.id)
        }
    }
}
