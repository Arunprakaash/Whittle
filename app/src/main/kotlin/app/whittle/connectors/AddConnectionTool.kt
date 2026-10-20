package app.whittle.connectors

import android.content.Context
import app.whittle.connectors.mcp.McpAuthMode
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
 * Adds an MCP server the user asked for. It always asks first (the card shows the address). Sign-in
 * happens in the browser or in Settings, never through the model: a token never passes through a chat.
 */
class AddConnectionTool(private val context: Context, private val connectors: Connectors) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val name = (arguments[NAME] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val url = (arguments[URL] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (url.isEmpty()) return reply("Give the server's address (url).", error = true)
        val connection = when (val added = connectors.add(name, url)) {
            is Outcome.Success -> added.value
            is Outcome.Failure -> return reply("Couldn't add it: ${added.error.message}", error = true)
        }
        val server = connection.server
        return when (server.auth) {
            McpAuthMode.NONE -> reply(
                "Connected ${server.name}. Its tools, starting with ${server.toolPrefix}: " +
                    connection.tools.joinToString { it.definition.id.value }.ifEmpty { "none yet" },
            )
            McpAuthMode.OAUTH -> {
                connectors.connect(this.context, connection)
                reply("Opened ${server.name}'s sign-in in the browser. Once the user signs in, its tools (starting with ${server.toolPrefix}) are available.")
            }
            McpAuthMode.TOKEN -> reply(
                "${server.name} needs an access token. Ask the user to tap ${server.name} in Settings › Connections and paste it there. " +
                    "Never ask for the token in the chat.",
            )
        }
    }

    private fun reply(text: String, error: Boolean = false) = ToolOutput(text, isError = error, provenance = Provenance.Trusted)

    companion object {
        private const val NAME = "name"
        private const val URL = "url"

        val DEFINITION = ToolDefinition(
            id = ToolId("add_connection"),
            description = "Adds an MCP server the user asked for, by its https address, so its tools become available. " +
                "The user approves first; Whittle then opens its sign-in, connects it, or tells you it needs an access token " +
                "the user adds in Settings. Only add servers the user named or clearly asked for.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"name":{"type":"string","description":"Short name for the service, e.g. Linear"},""" +
                    """"url":{"type":"string","description":"The server's MCP address, https"}},"required":["name","url"]}""",
            ).jsonObject,
            // Data can reach the new server; policy-rules.json makes every call ask (connections-always-confirm).
            riskTier = RiskTier.NETWORK_OUT,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_add_connection",
            outputProvenance = Provenance.Trusted,
        )
    }
}
