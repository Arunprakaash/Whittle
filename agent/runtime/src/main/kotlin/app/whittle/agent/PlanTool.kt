package app.whittle.agent

import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

/** One step of the agent's plan. */
@Serializable
data class PlanItem(val content: String, val status: PlanStatus)

@Serializable
enum class PlanStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("in_progress")
    IN_PROGRESS,

    @SerialName("completed")
    COMPLETED,
}

/**
 * The agent's to-do list for a task with several steps, rewritten whole each time it changes. It does
 * nothing on the phone: the app shows it as a checklist and as progress in the task's notification.
 */
class PlanTool(private val maxItems: Int) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val items = parse(arguments) ?: return failure("items must be a list of {content, status}")
        val problem = when {
            items.isEmpty() -> "the plan needs at least one step"
            items.size > maxItems -> "keep the plan to $maxItems steps or fewer"
            items.any { it.content.isBlank() } -> "every step needs a short description"
            items.count { it.status == PlanStatus.IN_PROGRESS } > 1 -> "only one step can be in progress"
            else -> null
        }
        if (problem != null) return failure(problem)
        val done = items.count { it.status == PlanStatus.COMPLETED }
        return ToolOutput("Plan saved: $done of ${items.size} steps done.", isError = false, provenance = Provenance.Trusted)
    }

    private fun failure(message: String) = ToolOutput(message, isError = true, provenance = Provenance.Trusted)

    companion object {
        const val ID = "update_plan"
        const val ITEMS = "items"
        private val JSON = Json { ignoreUnknownKeys = true }

        /** The steps in a call's arguments, or null when they don't parse. */
        fun parse(arguments: JsonObject): List<PlanItem>? = try {
            arguments[ITEMS]?.let { JSON.decodeFromJsonElement<List<PlanItem>>(it) }
        } catch (exception: SerializationException) {
            null
        } catch (exception: IllegalArgumentException) {
            null
        }

        val DEFINITION = ToolDefinition(
            id = ToolId(ID),
            description = "Your plan for a task with several steps, shown to the user as a checklist and as progress while " +
                "they're away. Call it before starting such a task, always with the whole list. Mark a step completed as " +
                "soon as it's done, not in a batch at the end, and set the next one in_progress; keep exactly one step " +
                "in_progress while working. If a step turns out to be unnecessary, remove it. Every step must be completed " +
                "before you give your final answer. Skip the plan for quick questions and single actions.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$ITEMS":{"type":"array","items":{"type":"object","properties":{""" +
                    """"content":{"type":"string","description":"The step in a few plain words, e.g. Find recent sources"},""" +
                    """"status":{"type":"string","enum":["pending","in_progress","completed"]}""" +
                    """},"required":["content","status"]}}},"required":["$ITEMS"]}""",
            ).jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_$ID",
            outputProvenance = Provenance.Trusted,
        )
    }
}
