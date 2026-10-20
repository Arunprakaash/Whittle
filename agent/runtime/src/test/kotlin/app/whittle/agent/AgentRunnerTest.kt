package app.whittle.agent

import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.HarnessError
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
import app.whittle.llm.api.HostedTool
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ReasoningEffort
import app.whittle.observability.LocalTelemetry
import app.whittle.policy.RuleBasedPolicyEngine
import app.whittle.policy.RunMode
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolRegistry
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
    fun `a follow-up continues the earlier conversation`() = runTest {
        val adapter = ScriptedAdapter(listOf(textTurn("Sure.")))
        val earlier = listOf(Message.userText("Plan dinner"), Message(Role.ASSISTANT, listOf(ContentBlock.Text("Toit at 8?"))))

        runner(adapter).start(request().copy(priorMessages = earlier)).toList()

        assertEquals(earlier + Message.userText("Hi"), adapter.requests.single().messages)
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
    fun `hosted web search is offered, shown as it runs, and marks the run as having read the web`() = runTest {
        val searchTurn = listOf(
            ModelEvent.HostedToolStarted("ws_1", HostedTool.WEB_SEARCH),
            ModelEvent.HostedToolFinished("ws_1", HostedTool.WEB_SEARCH, "search", buildJsonObject { put("query", "weather") }),
        ) + textTurn("Sunny.")
        val adapter = ScriptedAdapter(listOf(searchTurn))

        val events = runner(adapter).start(request()).toList()

        assertEquals(setOf(HostedTool.WEB_SEARCH), adapter.requests.single().hostedTools)
        assertEquals("ws_1", events.filterIsInstance<RunEvent.HostedToolStarted>().single().callId)
        assertEquals("search", events.filterIsInstance<RunEvent.HostedToolFinished>().single().action)
        val saved = savedCheckpoint()
        assertTrue(saved.tainted)
        val use = saved.conversation.messages.last().content.filterIsInstance<ContentBlock.HostedToolUse>().single()
        assertEquals("web_search", use.toolName)
    }

    @Test
    fun `web reads run without asking until untrusted content and personal data are both in the run`() = runTest {
        val contacts = FakeTool("contacts", RiskTier.READ, personalData = true)
        val page = FakeTool("page", RiskTier.WEB_READ, Provenance.Untrusted("web page"))
        val adapter = ScriptedAdapter(
            listOf(toolTurn(call("c1", "page")), toolTurn(call("c2", "contacts")), toolTurn(call("c3", "page")), textTurn("Done.")),
        )

        val events = runner(adapter, setOf(contacts, page)).start(request()).toList()

        assertEquals(1, page.executions)
        assertEquals("c3", events.filterIsInstance<RunEvent.DecisionRequested>().single().decision.toolCall.id.value)
        assertEquals(listOf(true, true, false, false), adapter.requests.map { HostedTool.WEB_SEARCH in it.hostedTools })
        assertTrue(savedCheckpoint().personalDataRead)
    }

    @Test
    fun `a follow-up keeps what earlier turns of the chat brought in`() = runTest {
        val adapter = ScriptedAdapter(listOf(textTurn("Sure.")))
        val earlier = listOf(
            Message.userText("Find Asha and read this page"),
            Message(Role.ASSISTANT, listOf(call("c1", "contacts"), call("c2", "page"))),
            Message(
                Role.USER,
                listOf(
                    ContentBlock.ToolResult(ToolCallId("c1"), "Asha 98400", isError = false, provenance = Provenance.Trusted, personalData = true),
                    ContentBlock.ToolResult(ToolCallId("c2"), "<html>", isError = false, provenance = Provenance.Untrusted("web page")),
                ),
            ),
        )

        runner(adapter).start(request().copy(priorMessages = earlier)).toList()

        assertEquals(emptySet(), adapter.requests.single().hostedTools)
        val saved = savedCheckpoint()
        assertTrue(saved.tainted && saved.personalDataRead)
    }

    @Test
    fun `a run that stops with plan steps open is sent back once to finish them`() = runTest {
        val plan = call("p1", PlanTool.ID, """{"items":[{"content":"Search","status":"completed"},{"content":"Compare","status":"in_progress"}]}""")
        val adapter = ScriptedAdapter(listOf(toolTurn(plan), textTurn("Early answer."), textTurn("Still done."), textTurn("unused")))

        val events = runner(adapter, setOf(PlanTool(maxItems = 5))).start(request()).toList()

        assertEquals(3, adapter.requests.size)
        assertEquals("unfinished: - Compare", lastUserText(adapter))
        assertEquals("Still done.", assertIs<RunEvent.Completed>(events.last()).finalMessage.text)
        assertTrue(savedCheckpoint().planReminded)
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
    fun `a question is parked as a card and the answer reaches the model`() = runTest {
        val ask = AskUserTool()
        val adapter = ScriptedAdapter(
            listOf(
                toolTurn(call("q1", AskUserTool.ID, """{"question":"Clock or widget?","options":["Clock","Widget"]}""")),
                textTurn("I'll wait for your pick."),
                textTurn("Building the clock."),
            ),
        )
        val runner = runner(adapter, setOf(ask))
        val first = runner.start(request()).toList()

        val requested = first.filterIsInstance<RunEvent.DecisionRequested>().single()
        assertEquals(DecisionKind.QUESTION, requested.decision.kind)
        assertEquals("question shown ask_user", lastToolResult(adapter).content)
        assertEquals(1, assertIs<RunEvent.Completed>(first.last()).openDecisions.size)

        inbox.submit(runId, RunInput.Answer(ToolCallId("q1"), "Clock"))
        val second = runner.resume(runId).toList()

        assertEquals("Clock", second.filterIsInstance<RunEvent.QuestionAnswered>().single().choice)
        assertEquals("answered Clock or widget?: Clock", lastUserText(adapter))
        assertTrue(savedCheckpoint().pendingDecisions.isEmpty())
    }

    @Test
    fun `a missing capability asks with a card and a grant lets the model continue`() = runTest {
        val contacts = PermissionedTool("contacts.search", CapabilityId("contacts"))
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "contacts.search")), textTurn("No access yet."), textTurn("Found Priya.")))
        val runner = runner(adapter, setOf(contacts))
        val first = runner.start(request()).toList()

        assertEquals(CapabilityId("contacts"), first.filterIsInstance<RunEvent.CapabilityRequested>().single().capability)
        assertEquals("contacts.search needs contacts", lastToolResult(adapter).content)
        assertEquals(setOf(CapabilityId("contacts")), savedCheckpoint().requestedCapabilities)

        contacts.granted = true
        inbox.submit(runId, RunInput.CapabilityChanged(CapabilityId("contacts"), granted = true))
        val second = runner.resume(runId).toList()

        assertEquals("granted contacts", lastUserText(adapter))
        assertIs<RunEvent.Completed>(second.last())
        assertTrue(savedCheckpoint().requestedCapabilities.isEmpty())
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
    fun `a stopped run leaves its whole conversation, with unfinished calls closed`() = runTest {
        val first = call("c1", "time.now")
        val second = call("c2", "time.now")
        val done = ContentBlock.ToolResult(first.id, "12:00", isError = false, provenance = Provenance.Trusted)
        store.save(
            baseCheckpoint().copy(
                conversation = Conversation("System", listOf(Message.userText("Build it"), Message(Role.ASSISTANT, listOf(first, second)))),
                stepResults = listOf(done),
            ),
        )

        val history = assertNotNull(runner().conversationAfter(runId))

        assertEquals("Build it", history.first().text)
        val results = history.last().content.filterIsInstance<ContentBlock.ToolResult>()
        assertEquals(listOf(done), results.take(1))
        assertEquals(second.id, results[1].toolCallId)
        assertTrue(results[1].isError)
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
    fun `a run cut off during its first reply can be continued`() = runTest {
        val hanging = object : app.whittle.llm.api.ModelAdapter {
            override val apiFormat = app.whittle.llm.api.ApiFormat.OPENAI_CHAT_COMPLETIONS
            override fun stream(request: app.whittle.llm.api.ModelRequest, provider: app.whittle.llm.api.ProviderDescriptor, apiKey: app.whittle.core.common.Secret) =
                kotlinx.coroutines.flow.flow<ModelEvent> { kotlinx.coroutines.awaitCancellation() }
        }
        val firstAttempt = runner(adapterRegistry = ModelAdapterRegistry(setOf(hanging)))
        val job = backgroundScope.launch { firstAttempt.start(request()).toList() }
        testScheduler.runCurrent()
        job.cancel()

        assertEquals(RunStatus.RUNNING, savedCheckpoint().status)
        val events = runner(ScriptedAdapter(listOf(textTurn("Here you go.")))).resume(runId).toList()
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
        val adapter = ScriptedAdapter(listOf(listOf(ModelEvent.Failed(app.whittle.core.common.HarnessError.Authentication("bad key")))))

        val events = runner(adapter).start(request()).toList()

        assertIs<RunEvent.Failed>(events.last())
        assertEquals(RunStatus.FAILED, savedCheckpoint().status)
    }

    @Test
    fun `a failure that passes is retried after a wait, and the run carries on`() = runTest {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.Failed(HarnessError.Provider(503, "busy"))),
                textTurn("Done."),
            ),
        )

        val events = runner(adapter).start(request()).toList()

        assertEquals(RunEvent.ModelRetrying(attempt = 1, delayMillis = 100), events.filterIsInstance<RunEvent.ModelRetrying>().single())
        assertIs<RunEvent.Completed>(events.last())
        assertEquals(2, adapter.requests.size)
    }

    @Test
    fun `a call is not repeated once part of the reply reached the user, nor for a bad key`() = runTest {
        val streamed = ScriptedAdapter(listOf(listOf(ModelEvent.TextDelta("Hal"), ModelEvent.Failed(HarnessError.Network("dropped")))))
        assertIs<RunEvent.Failed>(runner(streamed).start(request()).toList().last())
        assertEquals(1, streamed.requests.size)

        val badKey = ScriptedAdapter(listOf(listOf(ModelEvent.Failed(HarnessError.Provider(401, "bad key")))))
        assertIs<RunEvent.Failed>(runner(badKey).start(request()).toList().last())
        assertEquals(1, badKey.requests.size)
    }

    @Test
    fun `retries stop after the last attempt`() = runTest {
        val failure = listOf(ModelEvent.Failed(HarnessError.Network("offline")))
        val adapter = ScriptedAdapter(listOf(failure, failure, failure))

        val events = runner(adapter).start(request()).toList()

        assertIs<RunEvent.Failed>(events.last())
        assertEquals(testRetry.maxAttempts, adapter.requests.size)
        assertEquals(listOf(100L, 200L), events.filterIsInstance<RunEvent.ModelRetrying>().map { it.delayMillis })
    }

    @Test
    fun `a long output is saved in the workspace and the model gets its start, end and path`() = runTest {
        val output = "A".repeat(400) + "B".repeat(300)
        val saved = mutableListOf<String>()
        val store = ToolOutputStore { _, _, callId, content -> saved += content; Outcome.Success(".whittle/outputs/${callId.value}.txt") }
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "big")), textTurn("Done.")))
        val tool = FakeTool("big", RiskTier.READ) { output }

        runner(adapter, setOf(tool), outputs = store).start(request().copy(workspace = WorkspaceId("chat1"))).toList()

        assertEquals(listOf(output), saved)
        assertEquals("A".repeat(20) + "…" + "B".repeat(10) + " [700 saved at .whittle/outputs/c1.txt]", lastToolResult(adapter).content)
    }

    @Test
    fun `without a workspace a long output is cut instead`() = runTest {
        val store = ToolOutputStore { _, _, _, _ -> error("nowhere to save") }
        val adapter = ScriptedAdapter(listOf(toolTurn(call("c1", "big")), textTurn("Done.")))
        val tool = FakeTool("big", RiskTier.READ) { "x".repeat(2_000) }

        runner(adapter, setOf(tool), outputs = store).start(request()).toList()

        assertTrue(lastToolResult(adapter).content.endsWith("[truncated at 1000]"))
    }

    @Test
    fun `a conversation past the window is summarized first, keeping recent steps and the outside-content mark`() = runTest {
        val adapter = ScriptedAdapter(listOf(textTurn("SUMMARY"), textTurn("Done.")))
        val small = testContext.copy(fallbackContextTokens = 2_000)

        val events = runner(adapter, limits = testLimits(context = small)).start(request().copy(priorMessages = longHistory())).toList()

        val summarize = adapter.requests.first()
        assertEquals("Summarize.", summarize.systemPrompt)
        assertTrue(summarize.tools.isEmpty())
        assertTrue("[result from web, outside content]" in summarize.messages.single().text)
        val sent = adapter.requests.last().messages
        val summary = sent.first().content.single() as ContentBlock.Text
        assertEquals("summary: SUMMARY", summary.text)
        assertIs<Provenance.Untrusted>(summary.provenance)
        assertEquals(Role.ASSISTANT, sent[1].role)
        assertEquals("Earlier answer", sent[1].text)
        assertTrue(events.any { it is RunEvent.CompactionStarted })
        assertEquals(3, events.filterIsInstance<RunEvent.CompactionFinished>().single().summarizedMessages)
        assertIs<RunEvent.Completed>(events.last())
        assertTrue(savedCheckpoint().conversation.messages.first().carriesUntrustedContent())
    }

    @Test
    fun `when the provider says the conversation is too long, it is summarized and the call tried again`() = runTest {
        val adapter = ScriptedAdapter(
            listOf(
                listOf(ModelEvent.Failed(HarnessError.Provider(400, "This model's maximum is exceeded (context_length_exceeded)"))),
                textTurn("SUMMARY"),
                textTurn("Done."),
            ),
        )

        val events = runner(adapter).start(request().copy(priorMessages = longHistory())).toList()

        assertIs<RunEvent.Completed>(events.last())
        assertEquals(3, adapter.requests.size)
        assertEquals("summary: SUMMARY", adapter.requests.last().messages.first().text)
    }

    /** An earlier exchange: a question, a web read with a long result, then an answer. */
    private fun longHistory() = listOf(
        Message.userText("Find the opening hours"),
        Message(Role.ASSISTANT, listOf(call("w1", "fetch"))),
        Message(Role.USER, listOf(ContentBlock.ToolResult(ToolCallId("w1"), "page ".repeat(1_500), isError = false, provenance = Provenance.Untrusted("web")))),
        Message(Role.ASSISTANT, listOf(ContentBlock.Text("Earlier answer"))),
    )

    private fun runner(
        adapter: ScriptedAdapter? = null,
        tools: Set<Tool> = emptySet(),
        adapterRegistry: ModelAdapterRegistry = ModelAdapterRegistry(setOfNotNull(adapter)),
        limits: AgentLimits = testLimits(),
        outputs: ToolOutputStore? = null,
    ) = AgentRunner(
        adapters = adapterRegistry,
        providers = testCatalog,
        credentials = fixedKey,
        tools = ToolRegistry(tools),
        policy = RuleBasedPolicyEngine(testRules),
        inbox = inbox,
        checkpoints = store,
        telemetry = LocalTelemetry(IdGenerator.Random),
        limits = limits,
        systemPrompt = PromptTemplate(ConfigName("system.md"), "System for {{user}}"),
        messages = testMessages,
        clock = EpochClock { 0L },
        compactionPrompt = "Summarize.",
        outputs = outputs,
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
        status = RunStatus.RUNNING,
    )

    private fun lastUserText(adapter: ScriptedAdapter): String =
        adapter.requests.last().messages.last().content.filterIsInstance<ContentBlock.Text>().last().text

    private suspend fun savedCheckpoint(): RunCheckpoint = (store.load(runId) as Outcome.Success).value!!

    private fun lastToolResult(adapter: ScriptedAdapter): ContentBlock.ToolResult =
        adapter.requests.last().messages.last().content.filterIsInstance<ContentBlock.ToolResult>().last()

}
