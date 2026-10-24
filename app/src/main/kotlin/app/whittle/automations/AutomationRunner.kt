package app.whittle.automations

import app.whittle.agent.AgentMessages
import app.whittle.agent.PastedKind
import app.whittle.agent.pastedOutput
import app.whittle.core.common.EpochClock
import app.whittle.core.model.ContentBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext

enum class RunNowResult { STARTED, BUSY, NO_MODEL, NOT_FOUND }

/**
 * Starts automations in the app's chat session, each in a new chat titled after it. A due one waits while a task
 * is running or the user is in the app, so it never takes over the screen they're using; Run now is the user's
 * own tap and starts at once.
 */
class AutomationRunner(
    private val automations: Automations,
    private val chat: AutomationChat,
    private val clock: EpochClock,
    private val inForeground: () -> Boolean,
    /** Keeps the process alive for a run that started in the background: the run's foreground service. */
    private val protect: () -> Unit,
    private val gate: EventGate,
    private val messages: AgentMessages,
    /** The most characters of events one run is given. */
    private val maxPastedChars: Int,
    private val zones: () -> ZoneId = ZoneId::systemDefault,
    private val main: CoroutineContext = Dispatchers.Main,
) {
    /**
     * An alarm went off for [id], which was due at [scheduledForMillis]. Returns once the run has started and is
     * protected, or a retry is set, so the alarm's receiver can let the process go.
     */
    suspend fun due(id: String, scheduledForMillis: Long) {
        val automation = automations.get(id)?.takeIf { it.enabled } ?: return
        // The next run is set first, so a run that can't start never ends the schedule.
        automations.scheduleNext(id)
        // Both alarms of one due time can go off; the first one that ran wins. An event run has no such time: the
        // events it took are gone from the queue, so a second alarm finds nothing.
        if (!automation.isEvent && (automation.lastRunAtMillis ?: 0) >= scheduledForMillis) return
        val events = if (automation.isEvent) gate.take(id).ifEmpty { return } else emptyList()
        val pasted = listOfNotNull(eventsBlock(events))
        val started = chat.awaitRestored(automations.config.startTimeoutMillis) && withContext(main) {
            !inForeground() && !chat.isRunning && start(automation, pasted) == RunNowResult.STARTED
        }
        when {
            started -> protect()
            else -> {
                // Put back, never lost: even once given up on, the events stay queued for the next event or drain.
                gate.putBack(id, events)
                if (clock.nowMillis() - scheduledForMillis < automations.config.giveUpAfterMillis) automations.retryLater(id, scheduledForMillis)
                else automations.recordMissed(id, scheduledForMillis)
            }
        }
    }

    /**
     * Events were queued for [id]: runs them now when its cooldown is over, else wakes it when it ends. Called by the
     * event sources after they offer an event the gate accepted.
     */
    suspend fun eventsArrived(id: String) {
        val at = gate.pending()[id] ?: return
        if (at <= clock.nowMillis()) due(id, at) else automations.setDrain(id, at)
    }

    /** The user tapped Run now. */
    suspend fun runNow(id: String): RunNowResult {
        val automation = automations.get(id) ?: return RunNowResult.NOT_FOUND
        if (!chat.awaitRestored(automations.config.startTimeoutMillis)) return RunNowResult.BUSY
        return withContext(main) { start(automation) }
    }

    /** The events as one block of untrusted content, or null for a run without any. */
    private fun eventsBlock(events: List<PhoneEvent>): ContentBlock.Text? {
        if (events.isEmpty()) return null
        val zone = zones()
        val lines = events.map {
            val time = Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalTime().format(TIME)
            messages.eventLine(it.kind.name, it.source, time, it.text)
        }
        val text = (listOf(messages.automationEventsIntro) + lines).joinToString("\n")
        return pastedOutput(messages, PastedKind.PHONE_EVENT, events.first().source, text, maxPastedChars)
    }

    private suspend fun start(automation: Automation, pasted: List<ContentBlock.Text> = emptyList()): RunNowResult {
        if (!chat.hasModel) return RunNowResult.NO_MODEL
        val chatId = chat.start(automation, automations.preApprovedTools(automation), pasted) ?: return RunNowResult.BUSY
        automations.recordRun(automation.id, chatId, clock.nowMillis())
        return RunNowResult.STARTED
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
