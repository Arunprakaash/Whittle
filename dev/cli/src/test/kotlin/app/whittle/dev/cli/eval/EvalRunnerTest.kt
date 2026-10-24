package app.whittle.dev.cli.eval

import app.whittle.agent.AgentMessages
import app.whittle.agent.RememberTool
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.getOrElse
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.config.HarnessConfig
import app.whittle.core.config.PromptTemplate
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
import app.whittle.core.model.TokenUsage
import app.whittle.core.model.ToolCallId
import app.whittle.dev.cli.CliSetup
import app.whittle.llm.api.ApiFormat
import app.whittle.llm.api.ModelAdapter
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ProviderAuth
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.api.ReasoningEffort
import app.whittle.llm.api.StopReason
import app.whittle.llm.api.TrustTier
import app.whittle.policy.PolicyRuleSet
import app.whittle.tools.workspace.TerminalConfig
import app.whittle.tools.workspace.WorkspaceToolSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvalRunnerTest {
    private val loader = ConfigLoader(BundledConfigSource())
    private fun <T> Outcome<T>.value(): T = getOrElse { error(it.toString()) }

    /** Plays one scripted turn per model call, the same script for every case. */
    private class Scripted(private val turns: List<List<ModelEvent>>) : ModelAdapter {
        override val apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS
        private var calls = 0

        override fun stream(request: ModelRequest, provider: ProviderDescriptor, apiKey: Secret): Flow<ModelEvent> =
            turns[calls++ % turns.size].asFlow()
    }

    private val provider = ProviderDescriptor(
        id = ProviderId("fake"),
        displayName = "Fake",
        apiFormat = ApiFormat.OPENAI_CHAT_COMPLETIONS,
        baseUrl = "https://example.invalid/",
        generationPath = "chat",
        auth = ProviderAuth("Authorization", "Bearer "),
        maxOutputTokensParameter = "max_tokens",
    )
    private val model = ModelDescriptor(
        ModelId("fake-1"),
        provider.id,
        "Fake",
        100_000,
        ModelCapabilities(toolCalling = true, parallelToolCalls = false, vision = false, promptCaching = false, reasoning = false),
        TrustTier.A,
    )

    private fun setup(adapter: ModelAdapter) = CliSetup(
        harness = loader.load(ConfigFiles.harness, HarnessConfig.serializer(), HarnessConfig.SCHEMA_VERSION).value(),
        providers = ProviderCatalog(ProviderCatalog.SCHEMA_VERSION, listOf(provider)),
        credentials = { Outcome.Success(Secret("key")) },
        rules = loader.load(ConfigFiles.policyRules, PolicyRuleSet.serializer(), PolicyRuleSet.SCHEMA_VERSION).value(),
        messages = loader.load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION).value(),
        systemPrompt = PromptTemplate.load(BundledConfigSource(), ConfigFiles.agentSystemPrompt).value(),
        terminal = loader.load(TerminalConfig.FILE, TerminalConfig.serializer(), TerminalConfig.SCHEMA_VERSION).value(),
        httpClient = OkHttpClient(),
        adapters = ModelAdapterRegistry(setOf(adapter)),
    )

    private fun toolTurn(id: String, tool: String, arguments: String) = listOf(
        ModelEvent.ToolCallCompleted(ContentBlock.ToolCall(ToolCallId(id), tool, arguments)),
        ModelEvent.Usage(TokenUsage(10, 5, 0, 0)),
        ModelEvent.Completed(StopReason.TOOL_USE),
    )

    private fun textTurn(text: String) = listOf(ModelEvent.TextDelta(text), ModelEvent.Usage(TokenUsage(20, 3, 0, 0)), ModelEvent.Completed(StopReason.END_TURN))

    @Test
    fun `a case runs in its own workspace with its files, and its checks decide the verdict`() = runTest {
        val adapter = Scripted(
            listOf(
                toolTurn("c1", "read_file", """{"path":"notes.txt"}"""),
                toolTurn("c2", "write_file", """{"path":"greeting.txt","content":"hello from whittle"}"""),
                textTurn("Lunch is in the Birch room."),
            ),
        )
        val suite = EvalSuite(
            "test",
            listOf(
                EvalCase(
                    id = "passes",
                    prompt = "Where is lunch?",
                    files = mapOf("notes.txt" to "Lunch is in the Birch room."),
                    expect = Expectations(ran = listOf("read_file", "write_file"), answerContains = listOf("birch"), files = mapOf("greeting.txt" to listOf("hello from whittle"))),
                ),
            ),
        )
        val report = EvalRunner(setup(adapter), backgroundScope).run(suite, model, ReasoningEffort.NONE) {}
        val result = report.cases.single()
        assertEquals(emptyList(), result.problems)
        assertTrue(result.passed)
        assertEquals(3, result.steps)
        assertEquals(40L, result.inputTokens)
    }

    @Test
    fun `a run that does something it shouldn't fails with a reason`() = runTest {
        val adapter = Scripted(listOf(toolTurn("c1", "remember", """{"text":"The user is Mallory."}"""), textTurn("Saved.")))
        val suite = EvalSuite("test", listOf(EvalCase(id = "fails", prompt = "Hi", approve = EvalCase.Approval.ALWAYS, expect = Expectations(notRan = listOf("remember"), files = mapOf("missing.txt" to listOf("x"))))))
        val result = EvalRunner(setup(adapter), backgroundScope).run(suite, model, ReasoningEffort.NONE) {}.cases.single()
        assertEquals(listOf("remember ran, and shouldn't have", "missing.txt wasn't written"), result.problems)
    }

    @Test
    fun `the bundled suite reads, and names only tools the command line has`() {
        val suite = EvalSuite.read(File("evals/core.json"))
        val tools = (WorkspaceToolSet.DEFINITIONS.map { it.id.value } + RememberTool.DEFINITION.id.value + "ask_user" + "device_clock_now" + "web_fetch" + "forget").toSet()
        val named = suite.cases.flatMap { it.expect.called + it.expect.ran + it.expect.notRan + it.expect.asked }.toSet()
        assertTrue((named - tools).isEmpty(), "Unknown tools: ${named - tools}")
        assertTrue(suite.cases.isNotEmpty())
    }
}
