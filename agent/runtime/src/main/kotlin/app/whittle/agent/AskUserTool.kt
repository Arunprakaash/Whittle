package app.whittle.agent

import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import app.whittle.tools.api.UserQuestionTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lets the model ask the user to pick between a few options. Shown as an inline card that never
 * blocks the run; the answer comes back as a note. The harness handles it, so [execute] never runs.
 */
class AskUserTool : UserQuestionTool {
    override val definition = DEFINITION

    override fun question(arguments: JsonObject): String = arguments[QUESTION]?.jsonPrimitive?.contentOrNull.orEmpty()

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput =
        error("$ID is answered through a question card; the harness never executes it")

    companion object {
        const val ID = "ask_user"
        const val QUESTION = "question"
        const val OPTIONS = "options"

        val DEFINITION = ToolDefinition(
            id = ToolId(ID),
            description = "Ask the user to choose between 2 to 4 short options when you need their choice. " +
                "Use this instead of asking in text. It does not wait: carry on with anything that doesn't depend on the answer.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$QUESTION":{"type":"string","description":"One short question"},""" +
                    """"$OPTIONS":{"type":"array","items":{"type":"string"},"minItems":2,"maxItems":4,"description":"Short answers the user can tap"}""" +
                    """},"required":["$QUESTION","$OPTIONS"]}""",
            ).jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_$ID",
            outputProvenance = Provenance.Trusted,
        )
    }
}
