package app.whittle.automations

import app.whittle.core.common.EpochClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        // Both alarms of one due time can go off; the first one that ran wins.
        if ((automation.lastRunAtMillis ?: 0) >= scheduledForMillis) return
        val started = chat.awaitRestored(automations.config.startTimeoutMillis) && withContext(main) {
            !inForeground() && !chat.isRunning && start(automation) == RunNowResult.STARTED
        }
        when {
            started -> protect()
            clock.nowMillis() - scheduledForMillis < automations.config.giveUpAfterMillis -> automations.retryLater(id, scheduledForMillis)
            else -> automations.recordMissed(id, scheduledForMillis)
        }
    }

    /** The user tapped Run now. */
    suspend fun runNow(id: String): RunNowResult {
        val automation = automations.get(id) ?: return RunNowResult.NOT_FOUND
        if (!chat.awaitRestored(automations.config.startTimeoutMillis)) return RunNowResult.BUSY
        return withContext(main) { start(automation) }
    }

    private suspend fun start(automation: Automation): RunNowResult {
        if (!chat.hasModel) return RunNowResult.NO_MODEL
        val chatId = chat.start(automation, automations.preApprovedTools(automation)) ?: return RunNowResult.BUSY
        automations.recordRun(automation.id, chatId, clock.nowMillis())
        return RunNowResult.STARTED
    }
}
