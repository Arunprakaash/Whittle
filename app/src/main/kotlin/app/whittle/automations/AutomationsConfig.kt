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
    /** Automations to start from; each opens the editor filled in. Ones the user already has aren't offered again. */
    val ideas: List<AutomationIdea> = emptyList(),
) : VersionedConfig {
    init {
        require(maxAutomations > 0 && maxNameChars > 0 && maxPromptChars > 0) { "limits must be positive" }
        require(defaultMinuteOfDay in 0 until Schedule.MINUTES_PER_DAY) { "defaultMinuteOfDay must be within a day" }
        require(retryAfterMillis > 0 && giveUpAfterMillis >= retryAfterMillis && startTimeoutMillis > 0) { "retry times must be positive" }
        require(tools.map { it.key }.distinct().size == tools.size) { "tool choice keys must be unique" }
        val keys = tools.map { it.key }.toSet()
        ideas.forEach { idea ->
            require(idea.name.length <= maxNameChars && idea.prompt.length <= maxPromptChars) { "idea ${idea.name} is too long" }
            require(keys.containsAll(idea.tools)) { "idea ${idea.name} names tool choices that don't exist" }
        }
    }

    /** The tool ids an idea's choices stand for. */
    fun toolIdsFor(idea: AutomationIdea): Set<String> = tools.filter { it.key in idea.tools }.flatMap { it.toolIds }.toSet()

    /** Every tool id an automation may hold. */
    val choosableToolIds: Set<String> get() = tools.flatMap { it.toolIds }.toSet()

    val defaultToolIds: Set<String> get() = tools.filter { it.default }.flatMap { it.toolIds }.toSet()

    companion object {
        const val SCHEMA_VERSION = 1
        val FILE = ConfigName("automations.json")
    }
}

/** A ready-made automation to start from: what it's called and does, when, and which [tools] choices it starts with. */
@Serializable
data class AutomationIdea(
    val icon: String,
    val name: String,
    val prompt: String,
    val minuteOfDay: Int,
    /** ISO days of the week, as in [Schedule]. */
    val days: Set<Int>,
    /** Keys of [AutomationsConfig.tools]. */
    val tools: List<String>,
) {
    val schedule: Schedule get() = Schedule(minuteOfDay, days)

    init {
        // Builds the schedule once, so a time or day out of range fails when the config loads.
        schedule
    }
}

@Serializable
data class AutomationToolChoice(
    val key: String,
    val icon: String,
    /** For an automation without a project, which works on the whole workspace. */
    val label: String,
    /** For one with a project, where file tools reach only that folder; [label] when it makes no difference. */
    val labelInProject: String? = null,
    val toolIds: List<String>,
    /** What Android must grant first, asked when the user switches the row on (reading health data while closed, say). */
    val capabilities: List<String> = emptyList(),
    /** On for a new automation. */
    val default: Boolean,
) {
    fun labelFor(project: String?): String = if (project != null) labelInProject ?: label else label

    init {
        require(toolIds.isNotEmpty()) { "$key must name at least one tool" }
    }
}
