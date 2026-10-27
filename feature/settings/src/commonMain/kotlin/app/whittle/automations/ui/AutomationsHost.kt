package app.whittle.automations.ui

import app.whittle.automations.Trigger
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
    /** What the editor needs to set up a trigger. */
    val events: EventLimits = EventLimits(),
    /** The phone couldn't store waiting events, so a restart now would lose them. */
    val eventsNotSaved: Boolean = false,
)

/** automations.json's limits for triggers, as the editor uses them. */
@Serializable
data class EventLimits(
    val minIntervalMinutes: Int = 1,
    val defaultEveryMinutes: Int = 1,
    val defaultPlaceRadiusMeters: Int = 1,
    val minPlaceRadiusMeters: Int = 1,
    val maxPlaceNameChars: Int = 1,
    val maxNumberDigits: Int = 1,
    /** How late a change only a periodic look catches can be (leaving Wi-Fi, unplugging). */
    val placeCheckMinutes: Int = 1,
    val placePolling: Boolean = false,
    /** What the phone must grant for each kind of trigger, by its type: see [app.whittle.automations.capabilities]. */
    val capabilities: Map<String, List<String>> = emptyMap(),
)

/**
 * One automation; [days] are ISO weekdays (Monday is 1); [nextRunMillis] is null while it's off. With a [trigger] it
 * starts on that instead of [minuteOfDay] and [days], which stay as what the editor shows for a time.
 */
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
    val trigger: Trigger? = null,
    /** Events dropped today because it had run as often as it may. */
    val skippedToday: Int = 0,
    /** What its trigger needs that the phone hasn't granted (or that is switched off in Settings); it waits until then. */
    val missing: List<String> = emptyList(),
)

@Serializable
data class AutomationIdea(val icon: String, val name: String, val draft: AutomationEntry)

/**
 * A switch in the editor: the tools it covers, and what the phone must grant first (health data while closed, say).
 * One that [writes] can't be on for an automation that starts on a message.
 */
@Serializable
data class ToolChoice(
    val key: String,
    val icon: String,
    val label: String,
    val labelInProject: String? = null,
    val toolIds: List<String>,
    val capabilities: List<String> = emptyList(),
    val writes: Boolean = false,
) {
    fun labelFor(project: String?): String = if (project != null) labelInProject ?: label else label
}

@Serializable
enum class AutomationIssue { NameEmpty, NameTooLong, PromptEmpty, PromptTooLong, TooMany, ToolNotAllowed, ProjectInvalid, IntervalTooShort, SenderRequired }

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
 * need one stay as they are) and its notification prompt, short messages, and the phone's own readings for a
 * trigger ([phone], none on a computer).
 */
class AutomationsPlatform(
    val requestCapabilities: ((capabilities: List<String>, onDone: (granted: Boolean) -> Unit) -> Unit)?,
    val requestNotifications: () -> Unit,
    val toast: (String) -> Unit,
    val phone: PhoneReadings? = null,
)

/** What the trigger editor reads from the phone it runs on. */
class PhoneReadings(
    /** The apps with a launcher icon, by name, for a notification trigger. */
    val apps: suspend () -> List<AppChoice>,
    /** Where the phone is, latitude then longitude; null without location or a fix. */
    val location: suspend () -> Pair<Double, Double>?,
    /** The Wi-Fi network the phone is on: null when on none, empty when Android hides its name. */
    val wifi: () -> String?,
)

/** An app a notification trigger can watch: its package, and its name as the user sees it. */
data class AppChoice(val packageName: String, val label: String)

/** Where the Automations pages lead: back, to a chat, and to the chat a run just started. */
class AutomationsNavigation(
    val back: () -> Unit,
    val openChat: (chatId: String) -> Unit,
    val runStarted: () -> Unit,
)
