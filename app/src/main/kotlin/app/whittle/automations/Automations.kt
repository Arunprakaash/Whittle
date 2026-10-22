package app.whittle.automations

import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.tools.api.ToolId
import app.whittle.tools.workspace.ChatProjects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** Where automations are kept between launches. */
interface AutomationBackend {
    suspend fun load(): List<Automation>

    suspend fun save(automations: List<Automation>): Outcome<Unit>
}

/** Wakes the app when an automation is due. A retry is kept apart from the next run, so setting one never moves the other. */
interface AutomationAlarms {
    fun set(id: String, atMillis: Long, scheduledForMillis: Long, retry: Boolean)

    fun cancel(id: String)
}

/** Why an automation wasn't saved. */
enum class AutomationProblem { NAME_EMPTY, NAME_TOO_LONG, PROMPT_EMPTY, PROMPT_TOO_LONG, TOO_MANY, TOOL_NOT_ALLOWED, PROJECT_INVALID }

sealed interface SaveResult {
    data class Saved(val automation: Automation, val nextRunMillis: Long?) : SaveResult

    data class Refused(val problem: AutomationProblem) : SaveResult

    data class Failed(val error: HarnessError) : SaveResult
}

/**
 * The user's automations: kept on the phone, checked against automations.json, and each enabled one with an alarm
 * set for its next run. Every change sets or cancels alarms to match, so what's saved and what's scheduled agree.
 */
class Automations(
    private val backend: AutomationBackend,
    private val alarms: AutomationAlarms,
    val config: AutomationsConfig,
    private val clock: EpochClock,
    private val ids: IdGenerator,
    private val zones: () -> ZoneId,
    scope: CoroutineScope,
) {
    private val changes = Mutex()
    private val _all = MutableStateFlow<List<Automation>>(emptyList())
    private val loaded = scope.async { backend.load().also { _all.value = it } }

    /** Every automation, in the order they were made; empty until loaded. */
    val all: StateFlow<List<Automation>> = _all.asStateFlow()

    /** The time zone schedules are in: the phone's, as it is now. */
    val zone: ZoneId get() = zones()

    suspend fun get(id: String): Automation? = current().firstOrNull { it.id == id }

    /** A new automation as the editor starts it: the default time, every day, the default tools. */
    fun draft(name: String = "", prompt: String = ""): Automation = Automation(
        id = ids.newId(),
        name = name,
        prompt = prompt,
        schedule = Schedule(config.defaultMinuteOfDay, Schedule.EVERY_DAY),
        tools = config.defaultToolIds,
    )

    /**
     * Adds [automation], or replaces the one with its id, and sets its alarm. What its runs did is kept from the
     * stored one: an editor that was open during a run doesn't know about it.
     */
    suspend fun save(automation: Automation): SaveResult = changes.withLock {
        val current = current()
        val stored = current.firstOrNull { it.id == automation.id }
        val cleaned = automation.copy(
            name = automation.name.trim(),
            prompt = automation.prompt.trim(),
            project = automation.project?.trim()?.trim('/')?.ifEmpty { null },
            lastRunAtMillis = stored?.lastRunAtMillis,
            lastChatId = stored?.lastChatId,
            missedAtMillis = stored?.missedAtMillis,
        )
        problemWith(cleaned, isNew = stored == null, count = current.size)?.let { return@withLock SaveResult.Refused(it) }
        val updated = if (current.any { it.id == cleaned.id }) current.map { if (it.id == cleaned.id) cleaned else it } else current + cleaned
        when (val saved = store(updated)) {
            is Outcome.Failure -> SaveResult.Failed(saved.error)
            is Outcome.Success -> SaveResult.Saved(cleaned, schedule(cleaned))
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Outcome<Unit> = changes.withLock {
        val updated = current().map { if (it.id == id) it.copy(enabled = enabled) else it }
        store(updated).also { if (it is Outcome.Success) updated.firstOrNull { a -> a.id == id }?.let(::schedule) }
    }

    suspend fun delete(id: String): Outcome<Unit> = changes.withLock {
        alarms.cancel(id)
        store(current().filterNot { it.id == id })
    }

    /** Notes that [id] ran into [chatId], for the list and for Open last run. */
    suspend fun recordRun(id: String, chatId: String, atMillis: Long): Outcome<Unit> = changes.withLock {
        store(current().map { if (it.id == id) it.copy(lastRunAtMillis = atMillis, lastChatId = chatId, missedAtMillis = null) else it })
    }

    /** Notes that [id]'s run due at [scheduledForMillis] never started, so the list can say so. */
    suspend fun recordMissed(id: String, scheduledForMillis: Long): Outcome<Unit> = changes.withLock {
        store(current().map { if (it.id == id) it.copy(missedAtMillis = scheduledForMillis) else it })
    }

    /** Sets every alarm again: after a reboot, an update or a time zone change, and at each launch. */
    suspend fun rescheduleAll() = changes.withLock { current().forEach(::schedule) }

    /** Sets [id]'s next alarm from now. A run calls it before starting, so one failed run never ends the schedule. */
    suspend fun scheduleNext(id: String) = changes.withLock { current().firstOrNull { it.id == id }?.let(::schedule) }

    /** Tries [id] again shortly, keeping the time it was due so it can be given up on. */
    fun retryLater(id: String, scheduledForMillis: Long) {
        alarms.set(id, clock.nowMillis() + config.retryAfterMillis, scheduledForMillis, retry = true)
    }

    /** When [automation] runs next, or null while it's off. */
    fun nextRunMillis(automation: Automation): Long? =
        if (automation.enabled) automation.schedule.nextAfter(Instant.ofEpochMilli(clock.nowMillis()), zone).toEpochMilli() else null

    /** What a run of [automation] may use without asking. Only tools automations.json lets the user choose count. */
    fun preApprovedTools(automation: Automation): Set<ToolId> =
        (automation.tools.intersect(config.choosableToolIds) + config.alwaysPreApproved).map(::ToolId).toSet()

    private suspend fun current(): List<Automation> {
        loaded.await()
        return _all.value
    }

    private suspend fun store(automations: List<Automation>): Outcome<Unit> =
        backend.save(automations).also { if (it is Outcome.Success) _all.value = automations }

    private fun schedule(automation: Automation): Long? {
        val next = nextRunMillis(automation)
        if (next == null) alarms.cancel(automation.id) else alarms.set(automation.id, next, next, retry = false)
        return next
    }

    private fun problemWith(automation: Automation, isNew: Boolean, count: Int): AutomationProblem? = when {
        automation.name.isEmpty() -> AutomationProblem.NAME_EMPTY
        automation.name.length > config.maxNameChars -> AutomationProblem.NAME_TOO_LONG
        automation.prompt.isEmpty() -> AutomationProblem.PROMPT_EMPTY
        automation.prompt.length > config.maxPromptChars -> AutomationProblem.PROMPT_TOO_LONG
        !config.choosableToolIds.containsAll(automation.tools) -> AutomationProblem.TOOL_NOT_ALLOWED
        automation.project != null && !ChatProjects.isValidName(automation.project) -> AutomationProblem.PROJECT_INVALID
        isNew && count >= config.maxAutomations -> AutomationProblem.TOO_MANY
        else -> null
    }
}
