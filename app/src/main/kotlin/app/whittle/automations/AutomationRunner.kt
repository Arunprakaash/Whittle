package app.whittle.automations

import app.whittle.chat.ChatViewModel
import app.whittle.core.common.EpochClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class RunNowResult { STARTED, BUSY, NO_MODEL, NOT_FOUND }

/**
 * Starts automations in the app's chat session, each in a new chat titled after it. A due one waits while a task
 * is running or the user is in the app, so it never takes over the screen they're using; Run now is the user's
 * own tap and starts at once.
 */
class AutomationRunner(
    private val automations: Automations,
    private val chat: ChatViewModel,
    private val clock: EpochClock,
    private val inForeground: () -> Boolean,
) {
    /** An alarm went off for [id], which was due at [scheduledForMillis]. */
    suspend fun due(id: String, scheduledForMillis: Long) {
        val automation = automations.get(id)?.takeIf { it.enabled } ?: return
        // The next run is set first, so a run that can't start never ends the schedule.
        automations.scheduleNext(id)
        // Both alarms of one due time can go off; the first one that ran wins.
        if ((automation.lastRunAtMillis ?: 0) >= scheduledForMillis) return
        val started = restored() && withContext(Dispatchers.Main) {
            !inForeground() && !chat.state.value.running && start(automation) != RunNowResult.BUSY
        }
        if (!started && clock.nowMillis() - scheduledForMillis < automations.config.giveUpAfterMillis) {
            automations.retryLater(id, scheduledForMillis)
        }
    }

    /** The user tapped Run now. */
    suspend fun runNow(id: String): RunNowResult {
        val automation = automations.get(id) ?: return RunNowResult.NOT_FOUND
        if (!restored()) return RunNowResult.BUSY
        return withContext(Dispatchers.Main) { start(automation) }
    }

    /** On the main thread, where the chat session lives. */
    private suspend fun start(automation: Automation): RunNowResult {
        if (chat.state.value.model == null) return RunNowResult.NO_MODEL
        val chatId = chat.startAutomation(automation.name, automation.prompt, automations.preApprovedTools(automation))
            ?: return RunNowResult.BUSY
        automations.recordRun(automation.id, chatId.value, clock.nowMillis())
        return RunNowResult.STARTED
    }

    /** A run that woke the app waits for the saved chats, so the new chat doesn't land under a restoring one. */
    private suspend fun restored(): Boolean =
        withTimeoutOrNull(automations.config.startTimeoutMillis) { chat.state.first { !it.restoring } } != null
}
