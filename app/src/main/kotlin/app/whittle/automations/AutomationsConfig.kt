package app.whittle.automations

import app.whittle.core.config.ConfigName
import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable

/** Limits for automations and the tools the user may let them use without asking (automations.json). */
@Serializable
data class AutomationsConfig(
    override val schemaVersion: Int,
    val maxAutomations: Int,
    val maxNameChars: Int,
    val maxPromptChars: Int,
    /** The time of day a new automation starts at, in minutes after midnight. */
    val defaultMinuteOfDay: Int,
    /** While a task is running or the user is in the app, a due automation tries again this much later. */
    val retryAfterMillis: Long,
    /** A run that couldn't start within this long of its time is skipped until the next one. */
    val giveUpAfterMillis: Long,
    /** How long a run that woke the app waits for the saved chats to load. */
    val startTimeoutMillis: Long,
    /** Harness tools every automation has: writing a plan, and asking a question that waits for the user. */
    val alwaysPreApproved: List<String>,
    /** What the user can pre-approve, one row each; a row can stand for several tools. Never anything that sends data out. */
    val tools: List<AutomationToolChoice>,
) : VersionedConfig {
    init {
        require(maxAutomations > 0 && maxNameChars > 0 && maxPromptChars > 0) { "limits must be positive" }
        require(defaultMinuteOfDay in 0 until Schedule.MINUTES_PER_DAY) { "defaultMinuteOfDay must be within a day" }
        require(retryAfterMillis > 0 && giveUpAfterMillis >= retryAfterMillis && startTimeoutMillis > 0) { "retry times must be positive" }
        require(tools.map { it.key }.distinct().size == tools.size) { "tool choice keys must be unique" }
    }

    /** Every tool id an automation may hold. */
    val choosableToolIds: Set<String> get() = tools.flatMap { it.toolIds }.toSet()

    val defaultToolIds: Set<String> get() = tools.filter { it.default }.flatMap { it.toolIds }.toSet()

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("automations.json")
    }
}

@Serializable
data class AutomationToolChoice(
    val key: String,
    val icon: String,
    val label: String,
    val toolIds: List<String>,
    /** On for a new automation. */
    val default: Boolean,
) {
    init {
        require(toolIds.isNotEmpty()) { "$key must name at least one tool" }
    }
}
