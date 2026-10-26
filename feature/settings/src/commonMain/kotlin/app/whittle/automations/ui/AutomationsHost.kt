package app.whittle.automations.ui

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The user's automations, from wherever they're shown: the phone's own, or the same over the link on a computer. */
interface AutomationsHost {
    val state: StateFlow<AutomationsState>

    fun act(action: AutomationAction)

    /** Stores [automation], new or changed; what was stored, or why not. */
    suspend fun save(automation: AutomationEntry): AutomationSave

    /** Runs the automation with [id] now, in a new chat. */
    suspend fun runNow(id: String): RunNow
}

/** Everything the Automations pages show; times are the phone's, worked out in its time zone. */
@Serializable
data class AutomationsState(
    val automations: List<AutomationEntry> = emptyList(),
    /** What a new automation starts as, and what each idea fills it with. */
    val blank: AutomationEntry? = null,
    val ideas: List<AutomationIdea> = emptyList(),
    /** What an automation may use without asking, as switches. */
    val tools: List<ToolChoice> = emptyList(),
    /** The projects an automation can work in. */
    val projects: List<String> = emptyList(),
    val maxAutomations: Int = 0,
    val maxNameChars: Int = 0,
    val maxPromptChars: Int = 0,
    /** The phone's time zone, as an IANA id: when "07:30" is. */
    val zone: String = "UTC",
)

/** One automation; [days] are ISO weekdays (Monday is 1); [nextRunMillis] is null while it's off. */
@Serializable
data class AutomationEntry(
    val id: String,
    val name: String,
    val prompt: String,
    val minuteOfDay: Int,
    val days: Set<Int>,
    val tools: Set<String>,
    val project: String? = null,
    val enabled: Boolean = true,
    val lastRunAtMillis: Long? = null,
    val lastChatId: String? = null,
    val missedAtMillis: Long? = null,
    val nextRunMillis: Long? = null,
)

@Serializable
data class AutomationIdea(val icon: String, val name: String, val draft: AutomationEntry)

/** A switch in the editor: the tools it covers, and what the phone must grant first (health data while closed, say). */
@Serializable
data class ToolChoice(
    val key: String,
    val icon: String,
    val label: String,
    val labelInProject: String? = null,
    val toolIds: List<String>,
    val capabilities: List<String> = emptyList(),
) {
    fun labelFor(project: String?): String = if (project != null) labelInProject ?: label else label
}

@Serializable
enum class AutomationIssue { NameEmpty, NameTooLong, PromptEmpty, PromptTooLong, TooMany, ToolNotAllowed, ProjectInvalid }

@Serializable
sealed interface AutomationSave {
    @Serializable
    @SerialName("saved")
    data class Saved(val automation: AutomationEntry) : AutomationSave

    @Serializable
    @SerialName("refused")
    data class Refused(val issue: AutomationIssue) : AutomationSave

    /** The phone couldn't store it ([message] says why), or a computer's phone didn't answer (null). */
    @Serializable
    @SerialName("failed")
    data class Failed(val message: String? = null) : AutomationSave
}

@Serializable
enum class RunNow { Started, Busy, NoModel, NotFound }

@Serializable
sealed interface AutomationAction {
    @Serializable
    @SerialName("enabled")
    data class SetEnabled(val id: String, val on: Boolean) : AutomationAction

    @Serializable
    @SerialName("delete")
    data class Delete(val id: String) : AutomationAction
}

/**
 * What only the device does for Automations: Android's permission prompts (none on a computer, where switches that
 * need one stay as they are) and its notification prompt, and short messages.
 */
class AutomationsPlatform(
    val requestCapabilities: ((capabilities: List<String>, onDone: (granted: Boolean) -> Unit) -> Unit)?,
    val requestNotifications: () -> Unit,
    val toast: (String) -> Unit,
)

/** Where the Automations pages lead: back, to a chat, and to the chat a run just started. */
class AutomationsNavigation(
    val back: () -> Unit,
    val openChat: (chatId: String) -> Unit,
    val runStarted: () -> Unit,
)
