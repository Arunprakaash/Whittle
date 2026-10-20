package app.whittle.dev.cli

import app.whittle.agent.AgentMessages
import app.whittle.agent.AgentRunner
import app.whittle.agent.InMemoryRunCheckpointStore
import app.whittle.agent.RunEvent
import app.whittle.agent.RunRequest
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrElse
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.config.HarnessConfig
import app.whittle.core.config.NetworkConfig
import app.whittle.core.config.PromptTemplate
import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import app.whittle.dev.cli.tools.DeviceClockTool
import app.whittle.dev.cli.tools.WebFetchTool
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelDefaults
import app.whittle.llm.api.ModelTrustConfig
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.openai.OpenAiCompatibleAdapter
import app.whittle.llm.openai.OpenAiCompatibleModelLister
import app.whittle.llm.responses.OpenAiResponsesAdapter
import app.whittle.observability.LocalTelemetry
import app.whittle.policy.PolicyRuleSet
import app.whittle.policy.RuleBasedPolicyEngine
import app.whittle.policy.RunMode
import app.whittle.tools.api.ToolRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Developer harness: runs the real agent loop from the terminal with a key from a local file. */
fun main(args: Array<String>) = runBlocking {
    val arguments = CliArguments.parse(args).getOrElse {
        System.err.println(it.message)
        System.err.println(CliArguments.USAGE)
        exitProcess(EXIT_USAGE)
    }
    val source = BundledConfigSource()
    val loader = ConfigLoader(source)
    val harness = loader.load(ConfigFiles.harness, HarnessConfig.serializer(), HarnessConfig.SCHEMA_VERSION).orExit()
    val providers = loader.load(ConfigFiles.providerCatalog, ProviderCatalog.serializer(), ProviderCatalog.SCHEMA_VERSION).orExit()
    val trust = loader.load(ConfigFiles.modelTrust, ModelTrustConfig.serializer(), ModelTrustConfig.SCHEMA_VERSION).orExit()
    val modelDefaults = loader.load(ConfigFiles.modelDefaults, ModelDefaults.serializer(), ModelDefaults.SCHEMA_VERSION).orExit()
    val rules = loader.load(ConfigFiles.policyRules, PolicyRuleSet.serializer(), PolicyRuleSet.SCHEMA_VERSION).orExit()
    val messages = loader.load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION).orExit()
    val systemPrompt = PromptTemplate.load(source, ConfigFiles.agentSystemPrompt).orExit()

    val httpClient = httpClient(harness.network)
    val credentials = DevKeyFileCredentials(arguments.keyFile)
    val provider = providers.find(arguments.providerId)
        ?: fail("Unknown provider '${arguments.providerId.value}'. Known: ${providers.providers.map { it.id.value }}")
    val apiKey = credentials.apiKeyFor(provider.id).orExit()
    val models = OpenAiCompatibleModelLister(httpClient, trust, modelDefaults.fallbackCapabilities)
        .listModels(provider, apiKey).orExit()

    if (arguments.listModels) {
        models.sortedBy { it.id.value }.forEach { println("${it.id.value}  (trust tier ${it.trustTier})") }
        return@runBlocking
    }
    val modelId = arguments.modelId ?: fail("Pass --model (see --list-models)")
    val model = models.firstOrNull { it.id == modelId }
        ?: fail("Model '${modelId.value}' is not offered by ${provider.displayName}. Run with --list-models.")
    val prompt = arguments.prompt ?: fail("Pass --prompt")

    val clock = EpochClock.System
    val runner = AgentRunner(
        adapters = ModelAdapterRegistry(
            setOf(OpenAiCompatibleAdapter(httpClient, IdGenerator.Random), OpenAiResponsesAdapter(httpClient)),
        ),
        providers = providers,
        credentials = credentials,
        tools = ToolRegistry(setOf(DeviceClockTool(clock), WebFetchTool(httpClient))),
        policy = RuleBasedPolicyEngine(rules),
        approvals = ConsoleApprovalGateway(arguments.approvalMode),
        checkpoints = InMemoryRunCheckpointStore(),
        telemetry = LocalTelemetry(IdGenerator.Random),
        limits = harness.agent,
        systemPrompt = systemPrompt,
        messages = messages,
        clock = clock,
    )
    val reasoningEffort = arguments.reasoningEffort ?: modelDefaults.defaultReasoningEffort
    println(
        "[run] ${provider.displayName} / ${model.id.value} (trust tier ${model.trustTier}, " +
            "reasoning ${if (model.capabilities.reasoning) reasoningEffort else "not supported"})",
    )
    runner.start(
        RunRequest(
            runId = RunId(IdGenerator.Random.newId()),
            model = model,
            userMessage = Message.userText(prompt),
            mode = RunMode.LIVE,
            reasoningEffort = reasoningEffort,
            preApprovedTools = emptySet(),
            promptVariables = mapOf(
                "currentDate" to LocalDate.now().toString(),
                "userLocale" to Locale.getDefault().toLanguageTag(),
            ),
        ),
    ).collect(::render)
}

private fun render(event: RunEvent) {
    when (event) {
        is RunEvent.StepStarted -> println("\n[step ${event.step}]")
        is RunEvent.AssistantTextDelta -> print(event.text)
        is RunEvent.ToolCallDecided -> println(
            "\n[tool] ${event.toolCall.toolName} ${event.toolCall.argumentsJson} → ${event.decision::class.simpleName} " +
                "(${event.decision.reason::class.simpleName})",
        )
        is RunEvent.ToolCallFinished -> println("[tool] result${if (event.result.isError) " (error)" else ""}: ${preview(event.result.content)}")
        is RunEvent.Completed -> println("\n[done] tokens: ${event.usage}")
        is RunEvent.Paused -> println("\n[paused] ${event.reason}, tokens: ${event.usage}")
        is RunEvent.Failed -> println("\n[failed] ${event.error}")
    }
}

private fun preview(content: String): String = content.replace(WHITESPACE, " ").trim().take(PREVIEW_CHARS)

private val WHITESPACE = Regex("""\s+""")

private fun httpClient(network: NetworkConfig): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(network.connectTimeoutMillis, TimeUnit.MILLISECONDS)
    .readTimeout(network.readTimeoutMillis, TimeUnit.MILLISECONDS)
    .writeTimeout(network.writeTimeoutMillis, TimeUnit.MILLISECONDS)
    .build()

private fun <T> Outcome<T>.orExit(): T = getOrElse { fail(it.toString()) }

private fun fail(message: String): Nothing {
    System.err.println("error: $message")
    exitProcess(EXIT_FAILURE)
}

private const val PREVIEW_CHARS = 300
private const val EXIT_USAGE = 2
private const val EXIT_FAILURE = 1

