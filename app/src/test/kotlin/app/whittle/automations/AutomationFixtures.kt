package app.whittle.automations

import app.whittle.core.common.Outcome
import app.whittle.tools.api.ToolId

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
)

internal data class Alarm(val id: String, val atMillis: Long, val scheduledForMillis: Long, val retry: Boolean)

internal class RecordingAlarms : AutomationAlarms {
    val set = mutableListOf<Alarm>()
    val cancelled = mutableListOf<String>()

    override fun set(id: String, atMillis: Long, scheduledForMillis: Long, retry: Boolean) {
        set += Alarm(id, atMillis, scheduledForMillis, retry)
    }

    override fun cancel(id: String) {
        cancelled += id
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

    override suspend fun awaitRestored(timeoutMillis: Long): Boolean = restored

    override suspend fun start(automation: Automation, preApprovedTools: Set<ToolId>): String? {
        if (isRunning) return null
        started += automation to preApprovedTools
        return "chat-${started.size}"
    }
}
