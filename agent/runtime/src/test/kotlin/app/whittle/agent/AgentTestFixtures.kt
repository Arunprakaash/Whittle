package app.whittle.agent

import app.whittle.core.common.Outcome
import app.whittle.core.config.AgentLimits
import app.whittle.core.config.MemoryPolicy
import app.whittle.core.config.ModelRetryPolicy
import app.whittle.core.config.ToolOutputPolicy
import app.whittle.core.common.Secret
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.ModelId
import app.whittle.core.model.Provenance
import app.whittle.core.model.ProviderId
import app.whittle.core.model.TokenUsage
import app.whittle.core.model.ToolCallId
import app.whittle.llm.api.ApiFormat
import app.whittle.llm.api.HostedTool
import app.whittle.llm.api.HostedToolSupport
import app.whittle.llm.api.ModelAdapter
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ProviderAuth
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.api.StopReason
import app.whittle.llm.api.TrustTier
import app.whittle.policy.DefaultDecision
import app.whittle.policy.PolicyRuleSet
import app.whittle.policy.TrustTierLimit
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
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
    hostedTools = listOf(HostedToolSupport(HostedTool.WEB_SEARCH, listOf("*"))),
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
    awaitingUser = "waiting {{tool}}",
    decisionApproved = "approved {{tool}}: {{output}}",
    decisionApprovedUntrusted = "approved {{tool}} from {{source}}: {{output}}",
    decisionDeclined = "declined {{tool}}",
    decisionFailed = "approval failed {{tool}}: {{detail}}",
    questionShown = "question shown {{tool}}",
    questionAnswered = "answered {{question}}: {{answer}}",
    capabilityMissing = "{{tool}} needs {{capability}}",
    capabilityGranted = "granted {{capability}}",
    capabilityDenied = "denied {{capability}}",
    interruptedToolCall = "interrupted {{tool}}",
    toolFailed = "failed {{tool}}: {{detail}}",
    outputTruncated = "[truncated at {{limit}}]",
    planUnfinished = "unfinished: {{steps}}",
    outputSaved = "{{head}}…{{tail}} [{{chars}} saved at {{path}}]",
    memoryLine = "- {{text}} ({{id}})",
    noMemories = "none",
    memorySaved = "saved {{id}}",
    memoryEmpty = "empty",
    memoryTooLong = "too long, limit {{limit}}",
    memoryFull = "full at {{limit}}",
    memoryForgotten = "forgotten",
    memoryNotFound = "no {{id}}",
    automationScheduled = "scheduled {{name}} {{time}} {{days}} {{next}} {{place}} {{tools}}",
    automationInProject = "in {{project}}",
    automationInWorkspace = "everywhere",
    automationEveryDay = "daily",
    automationWeekdays = "weekdays",
    automationWeekends = "weekends",
    automationNoTools = "nothing",
    automationNameEmpty = "no name",
    automationNameTooLong = "name over {{limit}}",
    automationPromptEmpty = "no prompt",
    automationPromptTooLong = "prompt over {{limit}}",
    automationTooMany = "over {{limit}}",
    automationToolNotAllowed = "tool not allowed",
    automationProjectInvalid = "bad {{project}}",
    automationProjectMissing = "missing {{project}}",
    automationTimeInvalid = "bad time",
    automationDaysInvalid = "bad days",
    automationSaveFailed = "failed {{detail}}",
    attachments = "attached:\n{{files}}",
    attachmentLine = "- {{path}} ({{size}})",
    attachmentImagesUnseen = "images unseen",
    imageUnavailable = "no image {{path}}",
    appCreated = "created {{name}} in {{project}} as {{package}}",
    appNameEmpty = "no name",
    appNoWorkspace = "no workspace",
    appNoProject = "no project {{project}}",
    appSharedUserId = "shared user id",
    appPackageMismatch = "package must be {{expected}}",
    appBuildFailed = "build failed: {{errors}}",
    appBuildLogSaved = "log at {{path}}",
    appBuildNoApk = "no apk",
    appBuildTimedOut = "timed out after {{seconds}}",
)

internal val testRetry = ModelRetryPolicy(
    maxAttempts = 3,
    initialDelayMillis = 100,
    maxDelayMillis = 1_000,
    backoffMultiplier = 2.0,
    jitter = 0.0,
    retryableStatusCodes = setOf(429, 500, 503),
)

internal val testMemory = MemoryPolicy(maxItems = 2, maxItemChars = 40, idChars = 8)

internal val testToolOutput = ToolOutputPolicy(saveAboveChars = 500, previewHeadChars = 20, previewTailChars = 10, directory = ".whittle/outputs")

internal fun testLimits(maxToolOutputChars: Int = 1_000) = AgentLimits(
    maxStepsPerRun = 10,
    maxOutputTokensPerStep = 512,
    maxToolOutputChars = maxToolOutputChars,
    retry = testRetry,
    memory = testMemory,
    toolOutput = testToolOutput,
)

internal val testRules = PolicyRuleSet(
    schemaVersion = PolicyRuleSet.SCHEMA_VERSION,
    hardDeny = emptyList(),
    rules = emptyList(),
    riskTierDefaults = mapOf(
        RiskTier.READ to DefaultDecision.ALLOW,
        RiskTier.WORKSPACE to DefaultDecision.ALLOW,
        RiskTier.WEB_READ to DefaultDecision.ALLOW,
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
    personalData: Boolean = false,
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
        exposesPersonalData = personalData,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        executions++
        return ToolOutput(behaviour(), isError = false, provenance = provenance)
    }
}

/** A tool that reports a missing capability until [granted] is set. */
internal class PermissionedTool(id: String, private val capability: app.whittle.tools.api.CapabilityId) : Tool {
    var granted = false
    var executions = 0
        private set

    override val definition = ToolDefinition(
        id = ToolId(id),
        description = "Needs ${capability.value}",
        inputSchema = Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
        riskTier = RiskTier.READ,
        executionSite = ExecutionSite.DEVICE,
        requiredCapabilities = setOf(capability),
        consentTextKey = "consent_$id",
        outputProvenance = Provenance.Trusted,
    )

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        executions++
        return if (granted) {
            ToolOutput("data from ${definition.id.value}", isError = false, provenance = Provenance.Trusted)
        } else {
            ToolOutput("", isError = true, provenance = Provenance.Trusted, missingCapability = capability)
        }
    }
}

internal val fixedKey = app.whittle.llm.api.CredentialsProvider { Outcome.Success(Secret("key")) }
