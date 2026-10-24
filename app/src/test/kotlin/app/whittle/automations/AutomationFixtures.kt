package app.whittle.automations

import app.whittle.core.common.Outcome
import app.whittle.core.model.ContentBlock
import app.whittle.tools.api.ToolId

internal val testCodeMaskConfig = CodeMaskConfig(
    keywords = listOf("otp", "code", "verification", "verify", "pin", "password", "passcode", "one time", "one-time", "2fa", "login"),
    codePattern = "\\b(?:[A-Z]-)?\\d{4,8}\\b",
    nearChars = 40,
    replacement = "[code hidden]",
)

internal val testEventsConfig = EventsConfig(
    cooldownMillis = 120_000,
    maxRunsPerDay = 30,
    maxQueued = 20,
    minIntervalMinutes = 15,
    placeCheckMinutes = 15,
    placePolling = false,
    maxEventChars = 2000,
    codeMask = testCodeMaskConfig,
)

internal val testAutomationsConfig = AutomationsConfig(
    schemaVersion = AutomationsConfig.SCHEMA_VERSION,
    maxAutomations = 2,
    maxNameChars = 20,
    maxPromptChars = 100,
    defaultMinuteOfDay = 7 * Schedule.MINUTES_PER_HOUR,
    retryAfterMillis = 60_000,
    giveUpAfterMillis = 3_600_000,
    startTimeoutMillis = 1_000,
    alwaysPreApproved = listOf("update_plan"),
    tools = listOf(
        AutomationToolChoice(key = "web", icon = "travel_explore", label = "Web", toolIds = listOf("web_search", "web_fetch"), default = true),
        AutomationToolChoice(key = "calendar", icon = "calendar_month", label = "Calendar", toolIds = listOf("calendar_events"), default = false),
    ),
    events = testEventsConfig,
)

internal data class Alarm(val id: String, val atMillis: Long, val scheduledForMillis: Long, val retry: Boolean)

internal class RecordingAlarms : AutomationAlarms {
    val set = mutableListOf<Alarm>()
    val cancelled = mutableListOf<String>()
    val nextCancelled = mutableListOf<String>()

    override fun set(id: String, atMillis: Long, scheduledForMillis: Long, retry: Boolean) {
        set += Alarm(id, atMillis, scheduledForMillis, retry)
    }

    override fun cancel(id: String) {
        cancelled += id
    }

    override fun cancelNext(id: String) {
        nextCancelled += id
    }
}

internal class MemoryBackend : AutomationBackend {
    var stored: List<Automation> = emptyList()

    override suspend fun load(): List<Automation> = stored

    override suspend fun save(automations: List<Automation>): Outcome<Unit> {
        stored = automations
        return Outcome.Success(Unit)
    }
}

/** A chat session that starts whatever it's asked to unless told it's busy. */
internal class FakeChat(
    var restored: Boolean = true,
    override var isRunning: Boolean = false,
    override var hasModel: Boolean = true,
) : AutomationChat {
    val started = mutableListOf<Pair<Automation, Set<ToolId>>>()
    val pasted = mutableListOf<List<ContentBlock.Text>>()

    override suspend fun awaitRestored(timeoutMillis: Long): Boolean = restored

    override suspend fun start(automation: Automation, preApprovedTools: Set<ToolId>, pasted: List<ContentBlock.Text>): String? {
        if (isRunning) return null
        started += automation to preApprovedTools
        this.pasted += pasted
        return "chat-${started.size}"
    }
}

internal class MemoryEventBackend : EventQueueBackend {
    var stored: List<QueuedEvents> = emptyList()

    override suspend fun load(): List<QueuedEvents> = stored

    override suspend fun save(all: List<QueuedEvents>): Outcome<Unit> {
        stored = all
        return Outcome.Success(Unit)
    }
}
