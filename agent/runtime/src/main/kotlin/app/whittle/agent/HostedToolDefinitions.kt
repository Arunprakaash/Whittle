package app.whittle.agent

import app.whittle.core.model.Provenance
import app.whittle.llm.api.HostedTool
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolId
import kotlinx.serialization.json.JsonObject

/**
 * Hosted tools as the policy engine sees them. The provider runs them, so they can't be parked for a
 * decision: a hosted tool is offered to the model only on steps where policy would allow it outright.
 */
internal object HostedToolDefinitions {
    fun of(tool: HostedTool): ToolDefinition = when (tool) {
        HostedTool.WEB_SEARCH -> WEB_SEARCH
    }

    private val WEB_SEARCH = ToolDefinition(
        id = ToolId(HostedTool.WEB_SEARCH.toolName),
        description = "Searches the web and reads pages on the provider's servers.",
        inputSchema = JsonObject(emptyMap()),
        riskTier = RiskTier.WEB_READ,
        executionSite = ExecutionSite.PROVIDER,
        requiredCapabilities = emptySet(),
        consentTextKey = "consent_web_search",
        outputProvenance = Provenance.Untrusted("web search"),
    )
}
