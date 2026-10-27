package app.whittle.automations

import app.whittle.agent.AgentMessages
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import app.whittle.tools.api.WorkspaceId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** An app with a launcher icon, as the user knows it. */
data class LaunchableApp(val packageName: String, val label: String)

/** The apps a notification trigger can watch; behind an interface so the tool needs no phone to test. */
fun interface LaunchableApps {
    fun all(): List<LaunchableApp>
}

/**
 * Sets up an automation that starts on something that happens on the phone instead of at a time of day: a text, a
 * notification, plugging in, joining a Wi-Fi network, or every few minutes. Like [ScheduleTaskTool] it always asks and
 * takes the chat's project. It gives the new automation the default tools, except that one on a text or a notification
 * gets only [EventsConfig.messageDefaultTools]: no web, since what a stranger wrote could steer it. Places aren't
 * offered from chat: the model has no way to pick a spot on a map, so it sends the user to Settings.
 */
class WatchForTool(
    private val automations: Automations,
    private val messages: AgentMessages,
    projectOf: (WorkspaceId) -> String?,
    projectExists: (String) -> Boolean,
    private val apps: LaunchableApps,
) : Tool {
    private val support = AutomationToolSupport(automations, messages, projectOf, projectExists)

    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val request = parse(arguments)
        val event = request.event
        if (event in automations.config.events.placeEventWords) return support.reply(messages.automationPlaceInSettings, error = true)
        val trigger = when (val made = trigger(event, request)) {
            is Made.Chosen -> made.trigger
            is Made.Refused -> return support.reply(made.text, error = true)
        }
        val project = when (val chosen = support.project(request.project, context.workspace)) {
            is AutomationToolSupport.Project.Chosen -> chosen.name
            is AutomationToolSupport.Project.Refused -> return support.reply(chosen.text, error = true)
        }
        val tools = if (trigger.readsMessages) automations.config.messageDefaultToolIds else automations.config.defaultToolIds
        val draft = automations.draft(request.name, request.prompt).copy(schedule = null, trigger = trigger, tools = tools, project = project)
        return when (val saved = automations.save(draft)) {
            is SaveResult.Saved -> support.reply(describe(saved.automation, trigger, event))
            is SaveResult.Refused -> support.reply(support.refusal(saved.problem, project), error = true)
            is SaveResult.Failed -> support.reply(messages.automationSaveFailed(saved.error.message), error = true)
        }
    }

    private fun describe(automation: Automation, trigger: Trigger, event: String): String {
        val saved = messages.automationWatching(automation.name, event, support.place(automation.project), support.toolsOf(automation))
        val needed = trigger.capabilities(automations.config.events.capabilities)
        return if (needed.isEmpty()) saved else "$saved ${messages.automationNeedsPermission(needed)}"
    }

    private sealed interface Made {
        data class Chosen(val trigger: Trigger) : Made

        data class Refused(val text: String) : Made
    }

    private fun trigger(event: String, request: Request): Made = when (event) {
        "every" -> request.minutes?.let { Made.Chosen(Trigger.Every(it)) } ?: Made.Refused(messages.automationMinutesRequired)
        "text" -> Made.Chosen(Trigger.Sms(senders = list(request.from), words = list(request.words)))
        "notification" -> notification(request)
        "charging_start" -> Made.Chosen(Trigger.Charging(Edge.START))
        "charging_stop" -> Made.Chosen(Trigger.Charging(Edge.END))
        "wifi_join" -> Made.Chosen(Trigger.Wifi(request.from.ifBlank { null }, Edge.START))
        "wifi_leave" -> Made.Chosen(Trigger.Wifi(request.from.ifBlank { null }, Edge.END))
        else -> Made.Refused(messages.automationEventUnknown)
    }

    /** The app's name as the user sees it, matched ignoring case; one match wins, none or several are sent back to the model. */
    private fun notification(request: Request): Made {
        if (request.from.isBlank()) return Made.Refused(messages.automationAppRequired)
        val matches = apps.all().filter { it.label.equals(request.from, ignoreCase = true) }.distinctBy { it.packageName }
        return when (matches.size) {
            0 -> Made.Refused(messages.automationAppNotFound(request.from))
            1 -> Made.Chosen(Trigger.Notification(matches.single().packageName, matches.single().label, list(request.words)))
            else -> Made.Refused(messages.automationAppAmbiguous(request.from, matches.joinToString { "${it.label} (${it.packageName})" }))
        }
    }

    private data class Request(
        val name: String,
        val prompt: String,
        val event: String,
        val minutes: Int?,
        val from: String,
        val words: String,
        /** Null when not given, empty for the whole workspace. */
        val project: String?,
    )

    companion object {
        private const val NAME = "name"
        private const val PROMPT = "prompt"
        private const val EVENT = "event"
        private const val MINUTES = "minutes"
        private const val FROM = "from"
        private const val WORDS = "words"
        private const val PROJECT = "project"

        private val EVENTS = listOf("every", "text", "notification", "charging_start", "charging_stop", "wifi_join", "wifi_leave")

        private fun parse(arguments: JsonObject) = Request(
            name = arguments.string(NAME).orEmpty(),
            prompt = arguments.string(PROMPT).orEmpty(),
            event = arguments.string(EVENT).orEmpty().lowercase(),
            minutes = arguments.string(MINUTES)?.toIntOrNull(),
            from = arguments.string(FROM).orEmpty(),
            words = arguments.string(WORDS).orEmpty(),
            project = arguments.string(PROJECT)?.trim('/'),
        )

        /** A comma list without blanks. */
        private fun list(text: String): List<String> = text.split(',').map(String::trim).filter(String::isNotEmpty)

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()

        val DEFINITION = ToolDefinition(
            id = ToolId("watch_for"),
            description = "Sets up an automation that Whittle runs by itself when something happens on the phone, each run in a " +
                "new chat with a notification when it's done: a text arrives, an app posts a notification, the phone starts or " +
                "stops charging, it joins or leaves Wi-Fi, or every so many minutes. Use it when the user wants something done " +
                "in reaction (\"when I get a text from the bank\", \"whenever I plug in\"); use schedule_task for a time of day. " +
                "Write the prompt as the full instruction for one run, with everything it needs, since the run won't see this " +
                "chat. Places (arriving or leaving) can't be set up here: tell the user to add one in Settings › Automations. " +
                "The user approves first, and chooses in Settings which tools it may use without asking.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"name":{"type":"string","description":"Short name, e.g. Bank texts"},""" +
                    """"prompt":{"type":"string","description":"The instruction each run gets, complete on its own"},""" +
                    """"event":{"type":"string","enum":[${EVENTS.joinToString(",") { "\"$it\"" }}],""" +
                    """"description":"What starts it: every (needs minutes), text, notification (needs from), charging_start, charging_stop, wifi_join, wifi_leave"},""" +
                    """"minutes":{"type":"integer","description":"For every: minutes between runs"},""" +
                    """"from":{"type":"string","description":"For text: senders, comma separated, each a number or a sender ID exactly as the phone shows it (e.g. HDFCBK). For notification: the app's name as the user sees it. For wifi_join and wifi_leave: the network's name; leave out for any network"},""" +
                    """"words":{"type":"string","description":"For text and notification: comma separated words; it runs only when one of them is in the message. Leave out for any"},""" +
                    """"project":{"type":"string","description":"Project folder each run works in; leave out for this chat's project, or give an empty string for the whole workspace"}},""" +
                    """"required":["name","prompt","event"]}""",
            ).jsonObject,
            // It changes what the phone does later, by itself; policy-rules.json makes every call ask (event-automations-always-confirm).
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_watch_for",
            outputProvenance = Provenance.Trusted,
        )
    }
}
