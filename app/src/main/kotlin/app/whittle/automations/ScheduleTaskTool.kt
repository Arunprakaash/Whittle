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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.time.format.TextStyle
import java.util.Locale

/**
 * Sets up an automation the user asked for in the chat. It always asks first (the card shows the instruction).
 * The model can't choose what runs without asking: a new automation gets the default tools, and the user
 * changes them in Settings › Automations. Without a project it takes the chat's own.
 */
class ScheduleTaskTool(
    private val automations: Automations,
    private val messages: AgentMessages,
    /** The project a chat works in, while its folder exists. */
    private val projectOf: (WorkspaceId) -> String?,
    /** Whether a project folder of that name is in the workspace. */
    private val projectExists: (String) -> Boolean,
) : Tool {
    private val support = AutomationToolSupport(automations, messages, projectOf, projectExists)

    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val request = when (val parsed = parse(arguments)) {
            is Parsed.Request -> parsed
            Parsed.Invalid.TIME -> return reply(messages.automationTimeInvalid, error = true)
            Parsed.Invalid.DAYS -> return reply(messages.automationDaysInvalid, error = true)
        }
        val project = when (val chosen = support.project(request.project, context.workspace)) {
            is AutomationToolSupport.Project.Chosen -> chosen.name
            is AutomationToolSupport.Project.Refused -> return reply(chosen.text, error = true)
        }
        val draft = automations.draft(request.name, request.prompt).copy(schedule = request.schedule, project = project)
        return when (val saved = automations.save(draft)) {
            is SaveResult.Saved -> reply(describe(saved))
            is SaveResult.Refused -> reply(support.refusal(saved.problem, project), error = true)
            is SaveResult.Failed -> reply(messages.automationSaveFailed(saved.error.message), error = true)
        }
    }

    private fun describe(saved: SaveResult.Saved): String {
        val automation = saved.automation
        // This tool only ever makes time automations.
        val schedule = checkNotNull(automation.schedule)
        val next = saved.nextRunMillis?.let { Instant.ofEpochMilli(it).atZone(automations.zone) }
        return messages.automationScheduled(
            name = automation.name,
            time = schedule.time.toString(),
            days = days(schedule),
            next = next?.let { "${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${it.toLocalDate()} ${it.toLocalTime()}" }.orEmpty(),
            place = support.place(automation.project),
            tools = support.toolsOf(automation),
        )
    }

    private fun days(schedule: Schedule): String = when (schedule.days) {
        Schedule.EVERY_DAY -> messages.automationEveryDay
        Schedule.WEEKDAYS -> messages.automationWeekdays
        Schedule.WEEKENDS -> messages.automationWeekends
        else -> schedule.daysOfWeek.joinToString { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) }
    }

    private fun reply(text: String, error: Boolean = false) = support.reply(text, error)

    internal sealed interface Parsed {
        /** [project] is null when not given, empty for the whole workspace. */
        data class Request(val name: String, val prompt: String, val schedule: Schedule, val project: String?) : Parsed

        enum class Invalid : Parsed { TIME, DAYS }
    }

    companion object {
        private const val NAME = "name"
        private const val PROMPT = "prompt"
        private const val TIME = "time"
        private const val DAYS = "days"
        private const val PROJECT = "project"
        private val DAY_NAMES = DayOfWeek.entries.associateBy { it.name.take(3).lowercase(Locale.ROOT) }

        internal fun parse(arguments: JsonObject): Parsed {
            val time = try {
                LocalTime.parse(arguments.string(TIME).orEmpty())
            } catch (exception: DateTimeParseException) {
                return Parsed.Invalid.TIME
            }
            val days = when (val listed = arguments[DAYS]) {
                null -> Schedule.EVERY_DAY
                is JsonArray -> listed.map { (it as? JsonPrimitive)?.contentOrNull?.lowercase(Locale.ROOT)?.let(DAY_NAMES::get) ?: return Parsed.Invalid.DAYS }
                    .map { it.value }.toSet().ifEmpty { Schedule.EVERY_DAY }
                else -> return Parsed.Invalid.DAYS
            }
            return Parsed.Request(
                name = arguments.string(NAME).orEmpty(),
                prompt = arguments.string(PROMPT).orEmpty(),
                schedule = Schedule.at(time, days),
                project = arguments.string(PROJECT)?.trim('/'),
            )
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()

        val DEFINITION = ToolDefinition(
            id = ToolId("schedule_task"),
            description = "Sets up an automation: a task Whittle runs by itself at a time of day, on chosen days, each run in a new " +
                "chat with a notification when it's done. Use it when the user asks for something recurring (\"every morning\", " +
                "\"each Friday\"). Write the prompt as the full instruction for one run, with everything it needs, since the run " +
                "won't see this chat. Each run works in one project, the chat's own unless you give another; its file tools " +
                "reach nothing else. The user approves first, and chooses in Settings which tools it may use without asking.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"name":{"type":"string","description":"Short name, e.g. Morning brief"},""" +
                    """"prompt":{"type":"string","description":"The instruction each run gets, complete on its own"},""" +
                    """"time":{"type":"string","description":"Time of day, 24-hour HH:mm in the user's time zone"},""" +
                    """"days":{"type":"array","items":{"type":"string","enum":["mon","tue","wed","thu","fri","sat","sun"]},""" +
                    """"description":"Days it runs; leave out for every day"},""" +
                    """"project":{"type":"string","description":"Project folder each run works in; leave out for this chat's project, or give an empty string for the whole workspace"}},""" +
                    """"required":["name","prompt","time"]}""",
            ).jsonObject,
            // It changes what the phone does later, by itself; policy-rules.json makes every call ask (automations-always-confirm).
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_schedule_task",
            outputProvenance = Provenance.Trusted,
        )
    }
}
