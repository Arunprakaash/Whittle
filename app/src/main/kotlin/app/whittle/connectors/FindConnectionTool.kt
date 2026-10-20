package app.whittle.connectors

import app.whittle.connectors.mcp.LibraryServer
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
 * Finds MCP servers for a service the user wants to use: Whittle's library first, then the MCP
 * Registry. Registry entries come from anyone, so the output is untrusted.
 */
class FindConnectionTool(private val connectors: Connectors) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val query = (arguments[QUERY] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return ToolOutput("Say which service to look for (query).", isError = true, provenance = Provenance.Trusted)
        val featured = connectors.library.filter { it.matches(query) }
        val registry = when (val page = connectors.registry.search(query)) {
            is Outcome.Success -> page.value.servers.filter { found -> featured.none { it.url == found.url } }.take(MAX_REGISTRY)
            is Outcome.Failure -> emptyList()
        }
        if (featured.isEmpty() && registry.isEmpty()) return ToolOutput("No MCP server found for \"$query\".", isError = false, provenance = definition.outputProvenance)
        val text = buildString {
            if (featured.isNotEmpty()) {
                appendLine("From Whittle's library (official, checked):")
                featured.forEach { appendLine(line(it)) }
            }
            if (registry.isNotEmpty()) {
                appendLine("From the MCP Registry (published by anyone, not reviewed; tell the user that):")
                registry.forEach { appendLine(line(it)) }
            }
        }
        return ToolOutput(text.trim(), isError = false, provenance = definition.outputProvenance)
    }

    private fun line(server: LibraryServer): String {
        val added = if (connectors.added(server.url) != null) " (already connected)" else ""
        val keys = if (server.headers.any { it.isRequired }) " (needs a key the user adds in Settings)" else ""
        return "- ${server.title}: ${server.description.take(MAX_DESCRIPTION)} — ${server.url}$added$keys"
    }

    companion object {
        private const val QUERY = "query"
        private const val MAX_REGISTRY = 5
        private const val MAX_DESCRIPTION = 160

        val DEFINITION = ToolDefinition(
            id = ToolId("find_connection"),
            description = "Finds MCP servers for a service the user wants to use (e.g. \"Linear\", \"calendar\"). Returns servers from " +
                "Whittle's library, then matches from the public MCP Registry, with their addresses for add_connection.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"query":{"type":"string","description":"The service or task to find a server for"}},"required":["query"]}""",
            ).jsonObject,
            riskTier = RiskTier.WEB_READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_find_connection",
            outputProvenance = Provenance.Untrusted("MCP Registry"),
        )
    }
}

/** Whether [query] names this server, what it does, or something people look for it by. */
fun LibraryServer.matches(query: String): Boolean {
    val words = query.trim().lowercase()
    return words.isEmpty() || (listOf(title, description) + keywords).any { words in it.lowercase() }
}
