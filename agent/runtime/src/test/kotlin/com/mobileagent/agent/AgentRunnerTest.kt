package com.mobileagent.agent

import com.mobileagent.core.common.EpochClock
import com.mobileagent.core.common.IdGenerator
import com.mobileagent.core.common.Outcome
import com.mobileagent.core.config.AgentLimits
import com.mobileagent.core.config.ConfigName
import com.mobileagent.core.config.PromptTemplate
import com.mobileagent.core.model.ContentBlock
import com.mobileagent.core.model.Conversation
import com.mobileagent.core.model.Message
import com.mobileagent.core.model.Provenance
import com.mobileagent.core.model.Role
import com.mobileagent.core.model.RunId
import com.mobileagent.core.model.TokenUsage
import com.mobileagent.llm.api.ModelAdapterRegistry
import com.mobileagent.llm.api.ModelEvent
import com.mobileagent.llm.api.ReasoningEffort
import com.mobileagent.observability.LocalTelemetry
import com.mobileagent.policy.RuleBasedPolicyEngine
import com.mobileagent.policy.RunMode
import com.mobileagent.tools.api.RiskTier
import com.mobileagent.tools.api.Tool
import com.mobileagent.tools.api.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRunnerTest {
    private val store = InMemoryRunCheckpointStore()
    private val runId = RunId("run-1")

    @Test
    fun `text answer completes the run`() = runTest {
        val adapter = ScriptedAdapter(listOf(textTurn("Hello!")))
        val events = runner(adapter).start(request()).toList()

        val completed = assertIs<RunEvent.Completed>(events.last())
        assertEquals("Hello!", completed.finalMessage.text)
        assertEquals(ReasoningEffort.MEDIUM, adapter.requests.single().reasoningEffort)
        assertEquals(RunStatus.COMPLETED, savedCheckpoint().status)
    }

    @Test
    fun `allowed tool runs and its result goes back to the model`() = runTest {
        val clock = FakeTool("time.now", RiskTier.READ)
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "time.now")), textTurn("It is noon.")))

        val events = runner(adapter, setOf(clock)).start(request()).toList()

        assertEquals(1, clock.executions)
        assertIs<RunEvent.Completed>(events.last())
        val toolResult = adapter.requests[1].messages.last().content.single() as ContentBlock.ToolResult
        assertEquals("ok from time.now", toolResult.content)
    }

    @Test
    fun `untrusted output taints the run so the next outbound call asks again`() = runTest {
        val fetch = FakeTool("web.fetch", RiskTier.NETWORK_OUT, Provenance.Untrusted("web page"))
        val approvals = RecordingApprovals(ApprovalAnswer.APPROVED)
        val adapter = ScriptedAdapter(
            listOf(toolTurn(call("c1", "web.fetch")), toolTurn(call("c2", "web.fetch")), textTurn("done")),
        )

        runner(adapter, setOf(fetch), approvals).start(request()).toList()

        assertEquals(2, approvals.requests.size)
        assertEquals(2, fetch.executions)
        assertTrue(savedCheckpoint().tainted)
    }

    @Test
    fun `declined approval skips the tool`() = runTest {
        val fetch = FakeTool("web.fetch", RiskTier.NETWORK_OUT)
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "web.fetch")), textTurn("ok")))

        runner(adapter, setOf(fetch), RecordingApprovals(ApprovalAnswer.DECLINED)).start(request()).toList()

        assertEquals(0, fetch.executions)
        assertEquals("declined web.fetch", lastToolResult(adapter).content)
    }

    @Test
    fun `unknown tool is reported to the model and the run continues`() = runTest {
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "does.not.exist")), textTurn("sorry")))

        val events = runner(adapter).start(request()).toList()

        assertEquals("unknown does.not.exist", lastToolResult(adapter).content)
        assertIs<RunEvent.Completed>(events.last())
    }

    @Test
    fun `a crashing tool becomes an error result`() = runTest {
        val broken = FakeTool("broken", RiskTier.READ) { error("boom") }
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "broken")), textTurn("handled")))

        runner(adapter, setOf(broken)).start(request()).toList()

        val result = lastToolResult(adapter)
        assertTrue(result.isError)
        assertEquals("failed broken: boom", result.content)
    }

    @Test
    fun `token budget pauses the run and resume continues it`() = runTest {
        val clock = FakeTool("time.now", RiskTier.READ)
        val heavyUsage = TokenUsage(inputTokens = 900, outputTokens = 100, cacheReadTokens = 0, cacheWriteTokens = 0)
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "time.now"), usage = heavyUsage), textTurn("done")))
        val runner = runner(adapter, setOf(clock), budget = 500)

        val paused = assertIs<RunEvent.Paused>(runner.start(request()).toList().last())
        assertEquals(PauseReason.TOKEN_BUDGET, paused.reason)

        val resumed = runner.resume(runId, extraTokenBudget = 10_000).toList()
        assertIs<RunEvent.Completed>(resumed.last())
    }

    @Test
    fun `resume never re-runs a tool call that was in flight`() = runTest {
        val sender = FakeTool("message.send", RiskTier.READ)
        val inFlight = call("c1", "message.send")
        store.save(
            RunCheckpoint(
                runId = runId,
                model = testModel(),
                mode = RunMode.LIVE,
                reasoningEffort = ReasoningEffort.LOW,
                step = 1,
                conversation = Conversation(
                    systemPrompt = "System",
                    messages = listOf(Message.userText("send it"), Message(Role.ASSISTANT, listOf(inFlight))),
                ),
                tainted = false,
                preApprovedTools = emptySet(),
                approvedOnceTools = emptySet(),
                usage = TokenUsage.Zero,
                tokenBudget = DEFAULT_BUDGET,
                status = RunStatus.RUNNING,
                inFlightToolCall = inFlight,
            ),
        )
        val adapter = ScriptedAdapter(listOf(textTurn("I couldn't confirm it was sent.")))

        val events = runner(adapter, setOf(sender)).resume(runId).toList()

        assertEquals(0, sender.executions)
        assertEquals("interrupted message.send", lastToolResult(adapter).content)
        assertIs<RunEvent.Completed>(events.last())
    }

    @Test
    fun `missing prompt variable fails before calling the model`() = runTest {
        val adapter = ScriptedAdapter(emptyList())
        val events = runner(adapter).start(request().copy(promptVariables = emptyMap())).toList()

        assertIs<RunEvent.Failed>(events.single())
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun `model failure fails the run`() = runTest {
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.Failed(com.mobileagent.core.common.HarnessError.Network("offline")))))

        val events = runner(adapter).start(request()).toList()

        assertIs<RunEvent.Failed>(events.last())
        assertEquals(RunStatus.FAILED, savedCheckpoint().status)
    }

    private fun runner(
        adapter: ScriptedAdapter,
        tools: Set<Tool> = emptySet(),
        approvals: ApprovalGateway = RecordingApprovals(ApprovalAnswer.APPROVED),
        budget: Long = DEFAULT_BUDGET,
    ) = AgentRunner(
        adapters = ModelAdapterRegistry(setOf(adapter)),
        providers = testCatalog,
        credentials = fixedKey,
        tools = ToolRegistry(tools),
        policy = RuleBasedPolicyEngine(testRules),
        approvals = approvals,
        checkpoints = store,
        telemetry = LocalTelemetry(IdGenerator.Random),
        limits = AgentLimits(maxStepsPerRun = 10, maxOutputTokensPerStep = 512, tokenBudgetPerRun = budget, maxToolOutputChars = 1_000),
        systemPrompt = PromptTemplate(ConfigName("system.md"), "System for {{user}}"),
        messages = testMessages,
        clock = EpochClock { 0L },
    )

    private fun request() = RunRequest(
        runId = runId,
        model = testModel(),
        userMessage = Message.userText("Hi"),
        mode = RunMode.LIVE,
        reasoningEffort = ReasoningEffort.MEDIUM,
        preApprovedTools = emptySet(),
        promptVariables = mapOf("user" to "tester"),
    )

    private suspend fun savedCheckpoint(): RunCheckpoint = (store.load(runId) as Outcome.Success).value!!

    private fun lastToolResult(adapter: ScriptedAdapter): ContentBlock.ToolResult =
        adapter.requests.last().messages.last().content.filterIsInstance<ContentBlock.ToolResult>().last()

    private companion object {
        const val DEFAULT_BUDGET = 1_000_000L
    }
}
