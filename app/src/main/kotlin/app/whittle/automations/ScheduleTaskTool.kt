package app.whittle.automations

import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Sets up an automation the user asked for in the chat. It always asks first (the card shows the instruction).
 * The model can't choose what runs without asking: a new automation gets the default tools, and the user
 * changes them in Settings › Automations.
 */
class ScheduleTaskTool(private val automations: Automations, private val zone: () -> ZoneId = ZoneId::systemDefault) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val request = when (val parsed = parse(arguments)) {
            is Parsed.Request -> parsed
            is Parsed.Invalid -> return reply(parsed.reason, error = true)
        }
        val draft = automations.draft(request.name, request.prompt).copy(schedule = request.schedule)
        return when (val saved = automations.save(draft)) {
            is SaveResult.Saved -> reply(describe(saved))
            is SaveResult.Refused -> reply(refusal(saved.problem), error = true)
            is SaveResult.Failed -> reply("Couldn't save it: ${saved.error.message}", error = true)
        }
    }

    private fun describe(saved: SaveResult.Saved): String {
        val automation = saved.automation
        val next = saved.nextRunMillis?.let { NEXT_FORMAT.format(Instant.ofEpochMilli(it).atZone(zone())) }
        val labels = automations.config.tools.filter { automation.tools.containsAll(it.toolIds) }.map { it.label.lowercase(Locale.ROOT) }
        return "Scheduled \"${automation.name}\" at ${automation.schedule.time} on ${days(automation.schedule)}" +
            (next?.let { ", next on $it" } ?: "") + ". Each run starts a new chat and the user gets a notification when it's done. " +
            "It may ${labels.joinToString().ifEmpty { "use no tools" }} without asking; anything else waits for the user's approval. " +
            "They can change or turn it off in Settings › Automations."
    }

    private fun refusal(problem: AutomationProblem): String = when (problem) {
        AutomationProblem.NAME_EMPTY -> "Give it a short name."
        AutomationProblem.NAME_TOO_LONG -> "Use a name of at most ${automations.config.maxNameChars} characters."
        AutomationProblem.PROMPT_EMPTY -> "Say what to do each time (prompt)."
        AutomationProblem.PROMPT_TOO_LONG -> "Keep the prompt under ${automations.config.maxPromptChars} characters."
        AutomationProblem.TOO_MANY -> "The user already has ${automations.config.maxAutomations} automations; they can remove one in Settings › Automations."
        AutomationProblem.TOOL_NOT_ALLOWED -> "Those tools can't run unattended."
    }

    private fun reply(text: String, error: Boolean = false) = ToolOutput(text, isError = error, provenance = Provenance.Trusted)

    internal sealed interface Parsed {
        data class Request(val name: String, val prompt: String, val schedule: Schedule) : Parsed

        data class Invalid(val reason: String) : Parsed
    }

    companion object {
        private const val NAME = "name"
        private const val PROMPT = "prompt"
        private const val TIME = "time"
        private const val DAYS = "days"
        private val DAY_NAMES = DayOfWeek.entries.associateBy { it.name.take(3).lowercase(Locale.ROOT) }
        private val NEXT_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM 'at' HH:mm", Locale.ENGLISH)

        internal fun parse(arguments: JsonObject): Parsed {
            val name = arguments.string(NAME).orEmpty()
            val prompt = arguments.string(PROMPT).orEmpty()
            val time = try {
                LocalTime.parse(arguments.string(TIME).orEmpty())
            } catch (exception: DateTimeParseException) {
                return Parsed.Invalid("Give the time as 24-hour HH:mm, e.g. 07:30.")
            }
            val days = when (val listed = arguments[DAYS]) {
                null -> Schedule.EVERY_DAY
                is JsonArray -> listed.map { (it as? JsonPrimitive)?.contentOrNull?.lowercase(Locale.ROOT)?.let(DAY_NAMES::get) ?: return Parsed.Invalid("Name days as mon, tue, wed, thu, fri, sat or sun.") }
                    .map { it.value }.toSet().ifEmpty { Schedule.EVERY_DAY }
                else -> return Parsed.Invalid("days must be a list, e.g. [\"mon\", \"fri\"].")
            }
            return Parsed.Request(name, prompt, Schedule.at(time, days))
        }

        private fun days(schedule: Schedule): String = when (schedule.days) {
            Schedule.EVERY_DAY -> "every day"
            Schedule.WEEKDAYS -> "weekdays"
            Schedule.WEEKENDS -> "weekends"
            else -> schedule.daysOfWeek.joinToString { it.name.take(3).lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase) }
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()

        val DEFINITION = ToolDefinition(
            id = ToolId("schedule_task"),
            description = "Sets up an automation: a task Whittle runs by itself at a time of day, on chosen days, each run in a new " +
                "chat with a notification when it's done. Use it when the user asks for something recurring (\"every morning\", " +
                "\"each Friday\"). Write the prompt as the full instruction for one run, with everything it needs, since the run " +
                "won't see this chat. The user approves first, and chooses in Settings which tools it may use without asking.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"name":{"type":"string","description":"Short name, e.g. Morning brief"},""" +
                    """"prompt":{"type":"string","description":"The instruction each run gets, complete on its own"},""" +
                    """"time":{"type":"string","description":"Time of day, 24-hour HH:mm in the user's time zone"},""" +
                    """"days":{"type":"array","items":{"type":"string","enum":["mon","tue","wed","thu","fri","sat","sun"]},""" +
                    """"description":"Days it runs; leave out for every day"}},"required":["name","prompt","time"]}""",
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
