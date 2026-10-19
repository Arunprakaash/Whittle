package com.mobileagent.agent

import com.mobileagent.core.common.Outcome
import com.mobileagent.core.common.Secret
import com.mobileagent.core.model.ContentBlock
import com.mobileagent.core.model.ModelId
import com.mobileagent.core.model.Provenance
import com.mobileagent.core.model.ProviderId
import com.mobileagent.core.model.TokenUsage
import com.mobileagent.core.model.ToolCallId
import com.mobileagent.llm.api.ApiFormat
import com.mobileagent.llm.api.ModelAdapter
import com.mobileagent.llm.api.ModelCapabilities
import com.mobileagent.llm.api.ModelDescriptor
import com.mobileagent.llm.api.ModelEvent
import com.mobileagent.llm.api.ModelRequest
import com.mobileagent.llm.api.ProviderAuth
import com.mobileagent.llm.api.ProviderCatalog
import com.mobileagent.llm.api.ProviderDescriptor
import com.mobileagent.llm.api.StopReason
import com.mobileagent.llm.api.TrustTier
import com.mobileagent.policy.DefaultDecision
import com.mobileagent.policy.PolicyRuleSet
import com.mobileagent.policy.TrustTierLimit
import com.mobileagent.tools.api.ExecutionSite
import com.mobileagent.tools.api.RiskTier
import com.mobileagent.tools.api.Tool
import com.mobileagent.tools.api.ToolDefinition
import com.mobileagent.tools.api.ToolExecutionContext
import com.mobileagent.tools.api.ToolId
import com.mobileagent.tools.api.ToolOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

internal val testProvider = ProviderDescriptor(
    id = ProviderId("test"),
    displayName = "Test",
    apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,
    baseUrl = "https://test.invalid/",
    generationPath = "chat",
    auth = ProviderAuth("Authorization", "Bearer "),
    maxOutputTokensParameter = "max_tokens",
)

internal val testCatalog = ProviderCatalog(ProviderCatalog.SCHEMA_VERSION, listOf(testProvider))

internal fun testModel(tier: TrustTier = TrustTier.A) = ModelDescriptor(
    id = ModelId("test-model"),
    providerId = testProvider.id,
    displayName = "Test model",
    contextWindowTokens = null,
    capabilities = ModelCapabilities(toolCalling = true, parallelToolCalls = true, vision = false, promptCaching = false, reasoning = false),
    trustTier = tier,
)

internal val testMessages = AgentMessages(
    schemaVersion = AgentMessages.SCHEMA_VERSION,
    unknownTool = "unknown {{tool}}",
    invalidArguments = "invalid {{tool}}: {{detail}}",
    policyDenied = "denied {{tool}}",
    userDeclined = "declined {{tool}}",
    interruptedToolCall = "interrupted {{tool}}",
    toolFailed = "failed {{tool}}: {{detail}}",
    outputTruncated = "[truncated at {{limit}}]",
)

internal val testRules = PolicyRuleSet(
    schemaVersion = PolicyRuleSet.SCHEMA_VERSION,
    hardDeny = emptyList(),
    rules = emptyList(),
    riskTierDefaults = mapOf(
        RiskTier.READ to DefaultDecision.ALLOW,
        RiskTier.WRITE_LOCAL to DefaultDecision.ALLOW,
        RiskTier.NETWORK_OUT to DefaultDecision.ASK_ONCE,
        RiskTier.SEND_OR_SHARE to DefaultDecision.ASK_EVERY_TIME,
        RiskTier.DELETE_OR_PAY to DefaultDecision.ASK_EVERY_TIME,
    ),
    trustTierLimits = TrustTier.entries.associateWith {
        TrustTierLimit(deniedRiskTiers = emptySet(), askEveryTimeFrom = RiskTier.SEND_OR_SHARE)
    },
)

/** Replays one scripted list of events per model call. */
internal class ScriptedAdapter(private val turns: List<List<ModelEvent>>) : ModelAdapter {
    override val apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS
    val requests = mutableListOf<ModelRequest>()

    override fun stream(request: ModelRequest, provider: ProviderDescriptor, apiKey: Secret): Flow<ModelEvent> {
        requests += request
        return turns[requests.size - 1].asFlow()
    }
}

internal fun textTurn(text: String, usage: TokenUsage = TokenUsage.Zero) = listOf(
    ModelEvent.TextDelta(text),
    ModelEvent.Usage(usage),
    ModelEvent.Completed(StopReason.END_TURN),
)

internal fun toolTurn(vararg calls: ContentBlock.ToolCall, usage: TokenUsage = TokenUsage.Zero) =
    calls.map { ModelEvent.ToolCallCompleted(it) } + ModelEvent.Usage(usage) + ModelEvent.Completed(StopReason.TOOL_USE)

internal fun call(id: String, tool: String, arguments: String = "{}") = ContentBlock.ToolCall(ToolCallId(id), tool, arguments)

internal class FakeTool(
    id: String,
    risk: RiskTier,
    private val provenance: Provenance = Provenance.Trusted,
    private val behaviour: () -> String = { "ok from $id" },
) : Tool {
    var executions = 0
        private set

    override val definition = ToolDefinition(
        id = ToolId(id),
        description = "Fake $id",
        inputSchema = Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
        riskTier = risk,
        executionSite = ExecutionSite.DEVICE,
        requiredCapabilities = emptySet(),
        consentTextKey = "consent_$id",
        outputProvenance = provenance,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        executions++
        return ToolOutput(behaviour(), isError = false, provenance = provenance)
    }
}

internal class RecordingApprovals(private val answer: ApprovalAnswer) : ApprovalGateway {
    val requests = mutableListOf<ApprovalRequest>()

    override suspend fun request(request: ApprovalRequest): ApprovalAnswer {
        requests += request
        return answer
    }
}

internal val fixedKey = com.mobileagent.llm.api.CredentialsProvider { Outcome.Success(Secret("key")) }
