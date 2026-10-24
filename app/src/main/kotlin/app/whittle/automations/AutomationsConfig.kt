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
    /** Limits and masking for automations that run when something happens on the phone. */
    val events: EventsConfig,
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

/** Limits for event-triggered automations, and how one-time codes are hidden from them. */
@Serializable
data class EventsConfig(
    /** After an automation runs for an event, the next event for it waits this long. */
    val cooldownMillis: Long,
    val maxRunsPerDay: Int,
    /** Events kept while waiting for a run; the oldest are dropped past this. */
    val maxQueued: Int,
    /** The shortest "every" interval an automation may ask for, so none polls the phone awake. */
    val minIntervalMinutes: Int,
    val placeCheckMinutes: Int,
    val placePolling: Boolean,
    /** Text of one event kept past this length is cut. */
    val maxEventChars: Int,
    val codeMask: CodeMaskConfig,
) {
    init {
        require(cooldownMillis > 0 && maxRunsPerDay > 0 && maxQueued > 0) { "event limits must be positive" }
        require(minIntervalMinutes > 0 && placeCheckMinutes > 0 && maxEventChars > 0) { "event limits must be positive" }
    }
}

/** What counts as a one-time code and where it's hidden: a [codePattern] match with a keyword within [nearChars]. */
@Serializable
data class CodeMaskConfig(
    /** Words that say a number is a code (case-insensitive). */
    val keywords: List<String>,
    /** A regular expression for the numbers that might be codes; compiled ignoring case. */
    val codePattern: String,
    val nearChars: Int,
    /** What a hidden code is replaced with; says a code was there. */
    val replacement: String,
) {
    init {
        require(keywords.isNotEmpty() && keywords.all { it.isNotBlank() }) { "code keywords can't be empty" }
        require(nearChars > 0 && replacement.isNotEmpty()) { "code mask needs a distance and a replacement" }
        require(runCatching { Regex(codePattern) }.isSuccess) { "codePattern isn't a valid regular expression" }
    }
}

/**
 * A ready-made automation to start from: what it's called and does, when (a time of day on some days, or a
 * [trigger]; exactly one), and which [tools] choices it starts with.
 */
@Serializable
data class AutomationIdea(
    val icon: String,
    val name: String,
    val prompt: String,
    val minuteOfDay: Int? = null,
    /** ISO days of the week, as in [Schedule]. */
    val days: Set<Int>? = null,
    /** Keys of [AutomationsConfig.tools]. */
    val tools: List<String>,
    val trigger: Trigger? = null,
) {
    /** The schedule or the trigger this idea starts with, one of them null. */
    fun draftWhen(): Pair<Schedule?, Trigger?> =
        if (trigger != null) null to trigger else Schedule(requireNotNull(minuteOfDay), requireNotNull(days)) to null

    init {
        require((minuteOfDay == null) == (days == null)) { "$name needs both a time and days, or neither" }
        require((minuteOfDay == null) != (trigger == null)) { "$name needs a time or a trigger, not both or neither" }
        // Builds the schedule once, so a time or day out of range fails when the config loads.
        draftWhen()
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
