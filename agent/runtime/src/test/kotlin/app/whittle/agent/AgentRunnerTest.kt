package app.whittle.agent

import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.core.config.AgentLimits
import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Conversation
import app.whittle.core.model.Message
import app.whittle.core.model.Provenance
import app.whittle.core.model.Role
import app.whittle.core.model.RunId
import app.whittle.core.model.TokenUsage
import app.whittle.core.model.ToolCallId
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ReasoningEffort
import app.whittle.observability.LocalTelemetry
import app.whittle.policy.RuleBasedPolicyEngine
import app.whittle.policy.RunMode
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentRunnerTest {
    private val store = InMemoryRunCheckpointStore()
    private val runId = RunId("run-1")
    private val inbox = RunInbox()

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
    fun `a call that needs a decision is parked and the run finishes without waiting`() = runTest {
        val fetch = FakeTool("web.fetch", RiskTier.NETWORK_OUT)
        val clock = FakeTool("time.now", RiskTier.READ)
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "web.fetch"), call("c2", "time.now")), textTurn("Fetch is waiting on you.")))

        val events = runner(adapter, setOf(fetch, clock)).start(request()).toList()

        val requested = events.filterIsInstance<RunEvent.DecisionRequested>().single()
        assertEquals("c1", requested.decision.toolCall.id.value)
        assertEquals(0, fetch.executions)
        assertEquals(1, clock.executions)
        val toolResults = adapter.requests[1].messages.last().content.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(listOf("waiting web.fetch", "ok from time.now"), toolResults.map { it.content })
        val completed = assertIs<RunEvent.Completed>(events.last())
        assertEquals(listOf("c1"), completed.openDecisions.map { it.toolCall.id.value })
        assertEquals(RunStatus.COMPLETED, savedCheckpoint().status)
    }

    @Test
    fun `approving an open card later runs the exact call and continues the run`() = runTest {
        val fetch = FakeTool("web.fetch", RiskTier.NETWORK_OUT, Provenance.Untrusted("web page"))
        val adapter = ScriptedAdapter(
            listOf(toolTurn(call("c1", "web.fetch", """{"url":"https://a.test"}""")), textTurn("Waiting."), textTurn("Here it is.")),
        )
        val runner = runner(adapter, setOf(fetch))
        runner.start(request()).toList()

        inbox.submit(runId, RunInput.Decision(ToolCallId("c1"), DecisionAnswer.APPROVED))
        val events = runner.resume(runId).toList()

        assertEquals(1, fetch.executions)
        val resolved = events.filterIsInstance<RunEvent.DecisionResolved>().single()
        assertEquals(DecisionAnswer.APPROVED, resolved.answer)
        assertEquals("approved web.fetch from web page: ok from web.fetch", lastUserText(adapter))
        assertIs<RunEvent.Completed>(events.last())
        val saved = savedCheckpoint()
        assertTrue(saved.pendingDecisions.isEmpty())
        assertTrue(saved.tainted)
    }

    @Test
    fun `declining a decision tells the model and never runs the tool`() = runTest {
        val fetch = FakeTool("web.fetch", RiskTier.NETWORK_OUT)
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "web.fetch")), textTurn("Waiting."), textTurn("Okay.")))
        val runner = runner(adapter, setOf(fetch))
        runner.start(request()).toList()

        inbox.submit(runId, RunInput.Decision(ToolCallId("c1"), DecisionAnswer.DECLINED))
        val events = runner.resume(runId).toList()

        assertEquals(0, fetch.executions)
        assertEquals("declined web.fetch", lastUserText(adapter))
        assertIs<RunEvent.Completed>(events.last())
    }

    @Test
    fun `an answer given while the run works is applied before the next model call`() = runTest {
        val fetch = FakeTool("web.fetch", RiskTier.NETWORK_OUT)
        val slow = FakeTool("slow.read", RiskTier.READ) {
            inbox.submit(runId, RunInput.Decision(ToolCallId("c1"), DecisionAnswer.APPROVED))
            "read"
        }
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "web.fetch"), call("c2", "slow.read")), textTurn("All done.")))

        val events = runner(adapter, setOf(fetch, slow)).start(request()).toList()

        assertEquals(1, fetch.executions)
        assertEquals("approved web.fetch: ok from web.fetch", lastUserText(adapter))
        assertIs<RunEvent.Completed>(events.last())
    }

    @Test
    fun `instructions typed mid-run reach the model at the next step`() = runTest {
        val clock = FakeTool("time.now", RiskTier.READ) {
            inbox.submit(runId, RunInput.Steer("Also say I'll order the starters"))
            "noon"
        }
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "time.now")), textTurn("Done.")))

        runner(adapter, setOf(clock)).start(request()).toList()

        val lastUserTurn = adapter.requests[1].messages.last()
        assertEquals(Role.USER, lastUserTurn.role)
        assertIs<ContentBlock.ToolResult>(lastUserTurn.content.first())
        assertEquals("Also say I'll order the starters", lastUserText(adapter))
    }

    @Test
    fun `an approved call interrupted by process death is reported, not re-run`() = runTest {
        val sender = FakeTool("message.send", RiskTier.SEND_OR_SHARE)
        val interrupted = call("c1", "message.send")
        store.save(
            baseCheckpoint().copy(
                conversation = Conversation("System", listOf(Message.userText("send it"), Message(Role.ASSISTANT, listOf(ContentBlock.Text("Waiting."))))),
                status = RunStatus.RUNNING,
                inFlightDecision = interrupted,
            ),
        )
        val adapter = ScriptedAdapter(listOf(textTurn("I couldn't confirm it was sent.")))

        val events = runner(adapter, setOf(sender)).resume(runId).toList()

        assertEquals(0, sender.executions)
        assertEquals("interrupted message.send", lastUserText(adapter))
        assertIs<RunEvent.Completed>(events.last())
    }

    @Test
    fun `a completed run without open cards cannot be resumed`() = runTest {
        val adapter = ScriptedAdapter(listOf(textTurn("Hello!")))
        val runner = runner(adapter)
        runner.start(request()).toList()

        inbox.submit(runId, RunInput.Steer("one more thing"))
        val events = runner.resume(runId).toList()

        assertIs<RunEvent.Failed>(events.single())
        assertEquals(1, adapter.requests.size)
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
            baseCheckpoint().copy(
                conversation = Conversation("System", listOf(Message.userText("send it"), Message(Role.ASSISTANT, listOf(inFlight)))),
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
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.Failed(app.whittle.core.common.HarnessError.Network("offline")))))

        val events = runner(adapter).start(request()).toList()

        assertIs<RunEvent.Failed>(events.last())
        assertEquals(RunStatus.FAILED, savedCheckpoint().status)
    }

    private fun runner(
        adapter: ScriptedAdapter,
        tools: Set<Tool> = emptySet(),
        budget: Long = DEFAULT_BUDGET,
    ) = AgentRunner(
        adapters = ModelAdapterRegistry(setOf(adapter)),
        providers = testCatalog,
        credentials = fixedKey,
        tools = ToolRegistry(tools),
        policy = RuleBasedPolicyEngine(testRules),
        inbox = inbox,
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

    private fun baseCheckpoint() = RunCheckpoint(
        runId = runId,
        model = testModel(),
        mode = RunMode.LIVE,
        reasoningEffort = ReasoningEffort.LOW,
        step = 1,
        conversation = Conversation("System", emptyList()),
        tainted = false,
        preApprovedTools = emptySet(),
        approvedOnceTools = emptySet(),
        usage = TokenUsage.Zero,
        tokenBudget = DEFAULT_BUDGET,
        status = RunStatus.RUNNING,
    )

    private fun lastUserText(adapter: ScriptedAdapter): String =
        adapter.requests.last().messages.last().content.filterIsInstance<ContentBlock.Text>().last().text

    private suspend fun savedCheckpoint(): RunCheckpoint = (store.load(runId) as Outcome.Success).value!!

    private fun lastToolResult(adapter: ScriptedAdapter): ContentBlock.ToolResult =
        adapter.requests.last().messages.last().content.filterIsInstance<ContentBlock.ToolResult>().last()

    private companion object {
        const val DEFAULT_BUDGET = 1_000_000L
    }
}
