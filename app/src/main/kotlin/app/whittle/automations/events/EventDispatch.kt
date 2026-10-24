package app.whittle.automations.events

import app.whittle.automations.Automations
import app.whittle.automations.EventGate
import app.whittle.automations.GateDecision
import app.whittle.automations.PhoneEvent
import app.whittle.automations.matches

/**
 * Where every event source hands over what it saw: offers it to each enabled automation whose trigger matches, and
 * tells the runner about the ones the gate accepted. [eventsArrived] is the runner's, passed as a function so the
 * dispatcher needs no Android types.
 */
class EventDispatch(
    private val automations: Automations,
    private val gate: EventGate,
    private val eventsArrived: suspend (String) -> Unit,
) {
    suspend fun dispatch(event: PhoneEvent) {
        for (automation in automations.loadedAll()) {
            if (!automation.enabled || !automation.isEvent || automation.trigger?.matches(event) != true) continue
            if (gate.offer(automation.id, event) is GateDecision.RunAt) eventsArrived(automation.id)
        }
    }
}
