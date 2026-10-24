package app.whittle.dev.cli

import app.whittle.agent.AgentMessages
import app.whittle.agent.RunEvent
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrElse
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.config.HarnessConfig
import app.whittle.core.config.NetworkConfig
import app.whittle.core.config.PromptTemplate
import app.whittle.dev.cli.eval.EvalRunner
import app.whittle.dev.cli.eval.EvalSuite
import app.whittle.tools.workspace.TerminalConfig
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelDefaults
import app.whittle.llm.api.ModelTrustConfig
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.openai.OpenAiCompatibleAdapter
import app.whittle.llm.openai.OpenAiCompatibleModelLister
import app.whittle.llm.anthropic.AnthropicMessagesAdapter
import app.whittle.llm.responses.OpenAiResponsesAdapter
import app.whittle.policy.PolicyRuleSet
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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

    val setup = CliSetup(
        harness = harness,
        providers = providers,
        credentials = credentials,
        rules = rules,
        messages = messages,
        systemPrompt = systemPrompt,
        terminal = loader.load(TerminalConfig.FILE, TerminalConfig.serializer(), TerminalConfig.SCHEMA_VERSION).orExit(),
        httpClient = httpClient,
        adapters = ModelAdapterRegistry(setOf(OpenAiCompatibleAdapter(httpClient, IdGenerator.Random), OpenAiResponsesAdapter(httpClient), AnthropicMessagesAdapter())),
    )
    val reasoningEffort = arguments.reasoningEffort ?: modelDefaults.defaultReasoningEffort
    val runLabel = "${provider.displayName} / ${model.id.value} (trust tier ${model.trustTier}, " +
        "reasoning ${if (model.capabilities.reasoning) reasoningEffort else "not supported"})"

    arguments.evalSuite?.let { suiteFile ->
        val suite = runCatching { EvalSuite.read(suiteFile.toFile()) }.getOrElse { fail("Can't read ${suiteFile}: ${it.message}") }
        println("[eval] ${suite.name}: ${suite.cases.size} cases on $runLabel")
        val report = EvalRunner(setup, this).run(suite, model, reasoningEffort) { result ->
            val verdict = if (result.passed) "pass" else "FAIL"
            println("[eval] $verdict  ${result.id}  (${result.steps} steps, ${"%.1f".format(result.seconds)} s, ${result.inputTokens}+${result.outputTokens} tokens)")
            result.problems.forEach { println("         - $it") }
        }
        EvalRunner.write(report, arguments.reportFile.toFile())
        println("[eval] ${report.passed}/${report.cases.size} passed; report in ${arguments.reportFile}")
        exitProcess(if (report.passed == report.cases.size) 0 else EXIT_FAILURE)
    }

    val prompt = arguments.prompt ?: fail("Pass --prompt, or --eval with a suite")
    val session = CliSession(setup, arguments.approvalMode, arguments.memoryFile, this)
    println("[workspace] ${session.workspace}")
    println("[run] $runLabel")
    session.run(model, reasoningEffort, prompt, ::render, onContinue = { println("\n[run] continuing with your answers") })
}

private fun render(event: RunEvent) {
    when (event) {
        is RunEvent.StepStarted -> println("\n[step ${event.step}]")
        is RunEvent.AssistantTextDelta -> print(event.text)
        is RunEvent.ToolCallDecided -> println(
            "\n[tool] ${event.toolCall.toolName} ${event.toolCall.argumentsJson} → ${event.decision::class.simpleName} " +
                "(${event.decision.reason::class.simpleName})",
        )
        is RunEvent.SourceCited -> println("[source] ${event.title ?: ""} ${event.url}")
        is RunEvent.HostedToolStarted -> println("\n[hosted] ${event.tool.toolName} started")
        is RunEvent.HostedToolFinished -> println("[hosted] ${event.tool.toolName} ${event.action} ${event.arguments}")
        is RunEvent.ToolCallFinished -> println("[tool] result${if (event.result.isError) " (error)" else ""}: ${preview(event.result.content)}")
        is RunEvent.DecisionRequested -> Unit
        is RunEvent.QuestionAnswered -> println("[question] answered: ${event.choice}")
        is RunEvent.CapabilityRequested -> println("[capability] ${event.capability.value} is not available from the command line")
        is RunEvent.DecisionResolved -> println("[decision] ${event.decision.toolId.value} ${event.answer}${event.output?.let { ": " + preview(it.content) } ?: ""}")
        is RunEvent.Completed -> println("\n[done] tokens: ${event.usage}, open cards: ${event.openDecisions.size}")
        is RunEvent.Paused -> println("\n[paused] ${event.reason}, tokens: ${event.usage}")
        is RunEvent.Failed -> println("\n[failed] ${event.error}")
        is RunEvent.ModelRetrying -> println("\n[retry] attempt ${event.attempt} failed; trying again in ${event.delayMillis} ms")
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

