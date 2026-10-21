package app.whittle.connectors.mcp

import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOrigin
import app.whittle.tools.api.ToolOutput
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A connected server's tool, offered to the model under the connector's prefix. It runs in the user's own
 * cloud account, so its risk tier comes from Whittle's config, never from the server; its output is untrusted.
 */
internal class McpTool(
    private val remote: RemoteTool,
    server: McpServerConfig,
    private val connection: McpConnection,
) : Tool {
    override val definition = ToolDefinition(
        id = ToolId(server.toolPrefix + remote.name),
        description = "${server.name}: ${remote.description}".trim(),
        inputSchema = objectSchema(remote.inputSchema),
        riskTier = riskTier(remote, server),
        executionSite = ExecutionSite.USER_CLOUD,
        requiredCapabilities = emptySet(),
        consentTextKey = CONSENT_KEY,
        outputProvenance = Provenance.Untrusted(server.sourceLabel),
        origin = ToolOrigin(remote.displayName, server.name, (server.websiteUrl ?: server.url).toHttpUrlOrNull()?.host),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput =
        when (val result = connection.call(remote.name, arguments)) {
            is Outcome.Success -> ToolOutput(result.value.text, result.value.isError, definition.outputProvenance)
            is Outcome.Failure -> ToolOutput(result.error.message, isError = true, provenance = Provenance.Trusted)
        }

    private companion object {
        const val CONSENT_KEY = "consent_connector"
        const val TYPE = "type"
        const val OBJECT = "object"

        /** Whittle's tier for the tool; a server calling it destructive can only make it ask every time. */
        fun riskTier(remote: RemoteTool, server: McpServerConfig): RiskTier {
            val configured = server.riskTiers[remote.name] ?: server.defaultRiskTier
            return if (remote.annotations?.destructiveHint == true) maxOf(configured, RiskTier.DELETE_OR_PAY) else configured
        }

        /** Model APIs need a top-level object schema; some servers leave the type out for tools without arguments. */
        fun objectSchema(schema: JsonObject): JsonObject = if (TYPE in schema) schema else JsonObject(schema + (TYPE to JsonPrimitive(OBJECT)))
    }
}
