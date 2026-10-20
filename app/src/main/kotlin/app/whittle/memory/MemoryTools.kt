package app.whittle.memory

import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Saves a lasting fact about the user for future chats. It changes only Whittle's own data on the phone; once
 * a task has read outside content, policy asks the user first, so a web page can't plant a memory.
 */
class RememberTool(private val memory: MemoryStore) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val text = (arguments[TEXT] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (val saved = memory.remember(text)) {
            is Outcome.Success -> ToolOutput("Remembered (id ${saved.value.id}).", isError = false, provenance = Provenance.Trusted)
            is Outcome.Failure -> ToolOutput(saved.error.message, isError = true, provenance = Provenance.Trusted)
        }
    }

    companion object {
        private const val TEXT = "text"

        val DEFINITION = ToolDefinition(
            id = ToolId("remember"),
            description = "Remembers one lasting fact about the user for future chats: a preference, something about them or " +
                "their work, or something they asked you to remember. Write it as a short plain phrase about them, without " +
                "\"The user\": for example \"Prefers metric units\" or \"Is vegetarian\".",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$TEXT":{"type":"string","description":"The fact, in one short sentence"}},"required":["$TEXT"]}""",
            ).jsonObject,
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_remember",
            outputProvenance = Provenance.Trusted,
        )
    }
}

/** Forgets a remembered fact by the id shown beside it in the system prompt. */
class ForgetTool(private val memory: MemoryStore) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val id = (arguments[ID] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (val forgot = memory.forget(id)) {
            is Outcome.Success -> if (forgot.value) {
                ToolOutput("Forgotten.", isError = false, provenance = Provenance.Trusted)
            } else {
                ToolOutput("Nothing is remembered with id $id.", isError = true, provenance = Provenance.Trusted)
            }
            is Outcome.Failure -> ToolOutput(forgot.error.message, isError = true, provenance = Provenance.Trusted)
        }
    }

    companion object {
        private const val ID = "id"

        val DEFINITION = ToolDefinition(
            id = ToolId("forget"),
            description = "Forgets a fact you remembered about the user, by its id.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$ID":{"type":"string","description":"The id shown beside the fact"}},"required":["$ID"]}""",
            ).jsonObject,
            riskTier = RiskTier.WORKSPACE,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_forget",
            outputProvenance = Provenance.Trusted,
        )
    }
}
