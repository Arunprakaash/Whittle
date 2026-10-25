package app.whittle.agent

import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.flatMap
import app.whittle.core.common.getOrElse
import app.whittle.core.common.getOrNull
import app.whittle.core.common.map
import app.whittle.core.config.AgentLimits
import app.whittle.core.config.PromptTemplate
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Conversation
import app.whittle.core.model.Message
import app.whittle.tools.api.WorkspaceId
import app.whittle.core.model.Provenance
import app.whittle.core.model.Role
import app.whittle.core.model.RunId
import app.whittle.core.model.TokenUsage
import app.whittle.llm.api.AssistantTurnAssembler
import app.whittle.llm.api.CredentialsProvider
import app.whittle.llm.api.HostedTool
import app.whittle.llm.api.ModelAdapter
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.api.ToolSpec
import app.whittle.observability.AttributeKeys
import app.whittle.observability.Attributes
import app.whittle.observability.GenerationRecord
import app.whittle.observability.SpanContext
import app.whittle.observability.SpanName
import app.whittle.observability.SpanStatus
import app.whittle.observability.Telemetry
import app.whittle.observability.TelemetryEvent
import app.whittle.policy.ApprovalScope
import app.whittle.policy.PolicyDecision
import app.whittle.policy.PolicyEngine
import app.whittle.tools.api.CallPreview
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolArgumentParser
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import app.whittle.tools.api.ToolRegistry
import app.whittle.tools.api.UserQuestionTool
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * The agent loop: call the model, check every proposed tool call against the policy engine, run the
 * tool, and repeat. A call that needs the user's approval, or access to something on the phone, pauses the run
 * once the step's other calls are done; the model is called again only after every such card is answered, and
 * learns the outcome.
 * Questions (ask_user) don't pause: the model carries on with work that doesn't depend on the answer. The
 * user's answers and any mid-run instructions arrive through [RunInbox] and are applied at the next step
 * boundary.
 *
 * A checkpoint is saved after every step and before every tool call, so [resume] can continue after
 * process death, or after the user answers a waiting run, without repeating a side effect.
 */
class AgentRunner(
    private val adapters: ModelAdapterRegistry,
    private val providers: ProviderCatalog,
    private val credentials: CredentialsProvider,
    private val tools: ToolRegistry,
    private val policy: PolicyEngine,
    private val inbox: RunInbox,
    private val checkpoints: RunCheckpointStore,
    private val telemetry: Telemetry,
    private val limits: AgentLimits,
    private val systemPrompt: PromptTemplate,
    private val messages: AgentMessages,
    private val clock: EpochClock,
    /** Where long tool outputs are kept; without one they're cut to [AgentLimits.maxToolOutputChars]. */
    private val outputs: ToolOutputStore? = null,
    /** What the agent remembers about the user; every task's system prompt shows it. */
    private val memory: MemoryStore? = null,
    /** Reads attached images from the workspace for each model call; without one they're named in words. */
    private val images: ImageSource? = null,
    private val argumentParser: ToolArgumentParser = ToolArgumentParser(),
    private val random: Random = Random.Default,
) {

    fun start(request: RunRequest): Flow<RunEvent> = flow {
        val remembered = memory?.current().orEmpty().map { it.id to it.text }
        val variables = request.promptVariables + (SystemPromptVariables.MEMORIES to messages.memories(remembered))
        val prompt = systemPrompt.render(variables).getOrElse {
            emit(RunEvent.Failed(it))
            return@flow
        }
        val checkpoint = RunCheckpoint(
            runId = request.runId,
            model = request.model,
            mode = request.mode,
            reasoningEffort = request.reasoningEffort,
            step = 0,
            conversation = Conversation(prompt, request.priorMessages + request.userMessage),
            // Earlier turns of the chat are still in front of the model, and so is what they brought in; the new message may
            // carry output the user pasted from a card, which is untrusted too.
            tainted = (request.priorMessages + request.userMessage).any(Message::carriesUntrustedContent),
            personalDataRead = request.priorMessages.any(Message::carriesPersonalData),
            preApprovedTools = request.preApprovedTools,
            approvedOnceTools = emptySet(),
            usage = TokenUsage.Zero,
            status = RunStatus.RUNNING,
            workspace = request.workspace,
        )
        // Saved before the first model call, so a run cut off mid-reply can still be continued.
        persist(checkpoint).getOrElse {
            emit(RunEvent.Failed(it))
            return@flow
        }
        telemetry.event(TelemetryEvent.RUN_STARTED, runAttributes(checkpoint))
        drive(checkpoint)
    }

    /**
     * Continues a paused or interrupted run, or a completed one whose open decision the user just
     * answered. Submit the answer to the [RunInbox] first; it is applied before the next model call.
     */
    fun resume(runId: RunId): Flow<RunEvent> = flow {
        val checkpoint = checkpoints.load(runId).getOrElse {
            emit(RunEvent.Failed(it))
            return@flow
        }
        if (checkpoint == null || !checkpoint.canContinue()) {
            emit(RunEvent.Failed(HarnessError.NotFound("No resumable run ${runId.value}")))
            return@flow
        }
        val resumed = checkpoint.copy(status = RunStatus.RUNNING, pauseReason = null)
        telemetry.event(TelemetryEvent.RUN_RESUMED, runAttributes(resumed))
        drive(resumed)
    }

    /**
     * What a run leaves for the chat's next message, however it ended (finished, paused, failed or stopped):
     * its whole conversation, with tool calls it never got to closed as interrupted, so the next run sees
     * everything that happened and its first model call is well formed.
     */
    suspend fun conversationAfter(runId: RunId): List<Message>? {
        val checkpoint = checkpoints.load(runId).getOrNull() ?: return null
        val history = checkpoint.conversation.messages
        val open = history.lastOrNull()?.takeIf { it.role == Role.ASSISTANT }?.toolCalls.orEmpty()
        if (open.isEmpty()) return history
        val results = open.map { call ->
            checkpoint.stepResults.firstOrNull { it.toolCallId == call.id } ?: errorResult(call, messages.interruptedToolCall(call.toolName))
        }
        return history + Message(Role.USER, results)
    }

    private suspend fun FlowCollector<RunEvent>.drive(initial: RunCheckpoint) {
        val runSpan = telemetry.startSpan(SpanName.RUN, parent = null, runAttributes(initial))
        val final = runLoop(initial, runSpan.context)
        runSpan.end(
            status = if (final.status == RunStatus.FAILED) SpanStatus.ERROR else SpanStatus.OK,
            attributes = Attributes.of {
                metadata(AttributeKeys.RUN_STATUS, final.status.name)
                number(AttributeKeys.TOTAL_TOKENS, final.usage.totalTokens)
            },
        )
    }

    private suspend fun FlowCollector<RunEvent>.runLoop(initial: RunCheckpoint, runSpan: SpanContext): RunCheckpoint {
        val connection = connect(initial.model).getOrElse { return fail(initial, it) }
        var checkpoint = finishInterruptedStep(initial, runSpan)
            .flatMap { finishInterruptedDecision(it) }
            .getOrElse { return fail(initial, it) }
        while (true) {
            checkpoint = applyInbox(checkpoint, runSpan).getOrElse { return fail(checkpoint, it) }
            pauseReasonFor(checkpoint)?.let { return pause(checkpoint, it) }
            checkpoint = checkpoint.copy(step = checkpoint.step + 1)
            emit(RunEvent.StepStarted(checkpoint.step))
            val stepSpan = telemetry.startSpan(SpanName.STEP, runSpan, stepAttributes(checkpoint))

            val turn = callModelWithRetries(checkpoint, connection, stepSpan.context)
            turn.assembler.failure?.let {
                stepSpan.end(SpanStatus.ERROR)
                return fail(checkpoint, it)
            }
            val assistant = turn.assembler.toMessage()
            checkpoint = persist(
                checkpoint.copy(
                    conversation = checkpoint.conversation.append(assistant),
                    usage = checkpoint.usage + turn.assembler.usage,
                    tainted = checkpoint.tainted || assistant.carriesUntrustedContent(),
                ),
            ).getOrElse {
                stepSpan.end(SpanStatus.ERROR)
                return fail(checkpoint, it)
            }
            if (assistant.toolCalls.isEmpty()) {
                stepSpan.end(SpanStatus.OK)
                // The user said something while the model was answering: let the model see it.
                if (inbox.hasInput(checkpoint.runId)) continue
                // It stopped with plan steps open: send it back once to finish or tidy them, unless a card waits on the user.
                remindOfPlan(checkpoint)?.let { reminder ->
                    checkpoint = persist(reminder).getOrElse { return fail(checkpoint, it) }
                    continue
                }
                return complete(checkpoint, assistant)
            }
            checkpoint = runToolCalls(checkpoint, assistant.toolCalls, stepSpan.context).getOrElse {
                stepSpan.end(SpanStatus.ERROR)
                return fail(checkpoint, it)
            }
            stepSpan.end(SpanStatus.OK)
        }
    }

    private suspend fun connect(model: ModelDescriptor): Outcome<ModelConnection> {
        val provider = providers.find(model.providerId)
            ?: return Outcome.Failure(HarnessError.Configuration("Unknown provider ${model.providerId.value}"))
        return adapters.adapterFor(provider.apiFormat).flatMap { adapter ->
            credentials.apiKeyFor(provider.id).map { apiKey -> ModelConnection(adapter, provider, apiKey) }
        }
    }

    private fun pauseReasonFor(checkpoint: RunCheckpoint): PauseReason? = when {
        checkpoint.pendingDecisions.any { it.kind == DecisionKind.APPROVAL } || checkpoint.requestedCapabilities.isNotEmpty() ->
            PauseReason.AWAITING_USER
        checkpoint.step >= limits.maxStepsPerRun -> PauseReason.STEP_LIMIT
        else -> null
    }

    /**
     * Calls the model, trying again with growing waits when it fails for a passing reason. Only a call that
     * failed before anything reached the user is repeated, so the thread never shows a reply twice.
     */
    private suspend fun FlowCollector<RunEvent>.callModelWithRetries(
        checkpoint: RunCheckpoint,
        connection: ModelConnection,
        parent: SpanContext,
    ): ModelTurn {
        var attempt = 1
        while (true) {
            val turn = callModel(checkpoint, connection, parent)
            val failure = turn.assembler.failure ?: return turn
            if (attempt >= limits.retry.maxAttempts || turn.shownToUser || !isPassing(failure)) return turn
            val wait = backoff(attempt)
            telemetry.event(
                TelemetryEvent.MODEL_RETRIED,
                runAttributes(checkpoint) + Attributes.of {
                    number(AttributeKeys.ATTEMPT, attempt)
                    metadata(AttributeKeys.ERROR_TYPE, failure::class.simpleName)
                },
            )
            emit(RunEvent.ModelRetrying(attempt, wait))
            delay(wait)
            attempt++
        }
    }

    /** Network trouble, rate limits and busy servers pass; a bad key or a malformed request won't. */
    private fun isPassing(error: HarnessError): Boolean = when (error) {
        is HarnessError.Network -> true
        is HarnessError.Provider -> error.statusCode in limits.retry.retryableStatusCodes
        else -> false
    }

    private fun backoff(attempt: Int): Long {
        val policy = limits.retry
        val grown = policy.initialDelayMillis * policy.backoffMultiplier.pow(attempt - 1)
        val capped = grown.coerceAtMost(policy.maxDelayMillis.toDouble())
        return (capped * (1 - policy.jitter * random.nextDouble())).toLong()
    }

    /**
     * Attached images go to the model as images, read from the workspace now. One that can't be read (deleted,
     * or no workspace) is named in words instead, so the model knows something was there.
     */
    private suspend fun withImages(messages: List<Message>, workspace: WorkspaceId?): List<Message> {
        if (messages.none { message -> message.content.any { it is ContentBlock.ImageFile } }) return messages
        return messages.map { message ->
            if (message.content.none { it is ContentBlock.ImageFile }) return@map message
            message.copy(
                content = message.content.map { block ->
                    if (block !is ContentBlock.ImageFile) return@map block
                    val loaded = workspace?.let { images?.load(it, block.path) }
                    (loaded as? Outcome.Success)?.value ?: ContentBlock.Text(this.messages.imageUnavailable(block.path))
                },
            )
        }
    }

    private suspend fun FlowCollector<RunEvent>.callModel(
        checkpoint: RunCheckpoint,
        connection: ModelConnection,
        parent: SpanContext,
    ): ModelTurn {
        val request = ModelRequest(
            model = checkpoint.model,
            systemPrompt = checkpoint.conversation.systemPrompt,
            messages = withImages(checkpoint.conversation.messages, checkpoint.workspace),
            tools = tools.definitions.map { ToolSpec(it.id.value, it.description, it.inputSchema) },
            maxOutputTokens = connection.provider.maxOutputTokens ?: limits.maxOutputTokensPerStep,
            reasoningEffort = checkpoint.reasoningEffort,
            hostedTools = allowedHostedTools(checkpoint, connection.provider),
        )
        val assembler = AssistantTurnAssembler()
        var shown = false
        val startedAt = clock.nowMillis()
        connection.adapter.stream(request, connection.provider, connection.apiKey).collect { event ->
            assembler.accept(event)
            when (event) {
                is ModelEvent.TextDelta -> {
                    shown = true
                    emit(RunEvent.AssistantTextDelta(event.text))
                }
                is ModelEvent.HostedToolStarted -> {
                    shown = true
                    emit(RunEvent.HostedToolStarted(event.callId, event.tool))
                }
                is ModelEvent.SourceCited -> {
                    shown = true
                    emit(RunEvent.SourceCited(event.url, event.title))
                }
                is ModelEvent.HostedToolFinished -> {
                    shown = true
                    telemetry.event(TelemetryEvent.HOSTED_TOOL_USED, hostedToolAttributes(checkpoint, event))
                    emit(RunEvent.HostedToolFinished(event.callId, event.tool, event.action, event.arguments))
                }
                else -> Unit
            }
        }
        telemetry.generation(
            GenerationRecord(
                parent = parent,
                modelId = checkpoint.model.id.value,
                providerId = connection.provider.id.value,
                usage = assembler.usage,
                latencyMillis = clock.nowMillis() - startedAt,
                stopReason = assembler.stopReason?.name,
                errorType = assembler.failure?.let { it::class.simpleName },
            ),
        )
        return ModelTurn(assembler, shown)
    }

    /** Provider-run tools can't wait for a decision, so they are offered only on steps where policy allows them. */
    private fun allowedHostedTools(checkpoint: RunCheckpoint, provider: ProviderDescriptor): Set<HostedTool> =
        provider.hostedToolsFor(checkpoint.model.id).filterTo(mutableSetOf()) { tool ->
            policy.evaluate(HostedToolDefinitions.of(tool), checkpoint.runContext()) is PolicyDecision.Allow
        }

    /** On resume: report an in-flight tool call as interrupted, then finish the step's remaining calls. */
    private suspend fun FlowCollector<RunEvent>.finishInterruptedStep(
        checkpoint: RunCheckpoint,
        parent: SpanContext,
    ): Outcome<RunCheckpoint> {
        val lastMessage = checkpoint.conversation.messages.lastOrNull()
        if (lastMessage == null || lastMessage.role != Role.ASSISTANT || lastMessage.toolCalls.isEmpty()) {
            return Outcome.Success(checkpoint)
        }
        val afterInterruption = checkpoint.inFlightToolCall?.let { call ->
            recordResult(
                checkpoint.copy(inFlightToolCall = null),
                errorResult(call, messages.interruptedToolCall(call.toolName)),
            ).getOrElse { return Outcome.Failure(it) }
        } ?: checkpoint
        return runToolCalls(afterInterruption, lastMessage.toolCalls, parent)
    }

    private suspend fun FlowCollector<RunEvent>.runToolCalls(
        start: RunCheckpoint,
        calls: List<ContentBlock.ToolCall>,
        parent: SpanContext,
    ): Outcome<RunCheckpoint> {
        var checkpoint = start
        for (call in calls) {
            if (checkpoint.stepResults.any { it.toolCallId == call.id }) continue
            checkpoint = handleToolCall(checkpoint, call, parent).getOrElse { return Outcome.Failure(it) }
        }
        val resultsMessage = Message(Role.USER, checkpoint.stepResults)
        return persist(checkpoint.copy(conversation = checkpoint.conversation.append(resultsMessage), stepResults = emptyList()))
    }

    private suspend fun FlowCollector<RunEvent>.handleToolCall(
        checkpoint: RunCheckpoint,
        call: ContentBlock.ToolCall,
        parent: SpanContext,
    ): Outcome<RunCheckpoint> {
        val tool = tools.find(ToolId(call.toolName))
            ?: return recordResult(checkpoint, errorResult(call, messages.unknownTool(call.toolName)))
        val arguments = argumentParser.parse(call.argumentsJson, tool.definition.inputSchema).getOrElse {
            return recordResult(checkpoint, errorResult(call, messages.invalidArguments(call.toolName, it.message)))
        }
        // Asking the user is always allowed and never acts, so questions skip the policy engine.
        if (tool is UserQuestionTool) return park(checkpoint, tool, call, arguments, ApprovalScope.EVERY_CALL, DecisionKind.QUESTION)
        val decision = policy.evaluate(tool.definition, checkpoint.runContext())
        telemetry.event(TelemetryEvent.POLICY_DECISION, decisionAttributes(checkpoint, tool, decision))
        emit(RunEvent.ToolCallDecided(call, decision))

        return when (decision) {
            is PolicyDecision.Deny -> recordResult(checkpoint, errorResult(call, messages.policyDenied(call.toolName)))
            is PolicyDecision.Allow -> execute(checkpoint, tool, call, arguments, parent)
            is PolicyDecision.Ask -> park(checkpoint, tool, call, arguments, decision.scope, DecisionKind.APPROVAL)
        }
    }

    /**
     * Parks a call or question for the user and tells the model it is waiting. An approval pauses the run at
     * the end of this step; a question doesn't.
     */
    private suspend fun FlowCollector<RunEvent>.park(
        checkpoint: RunCheckpoint,
        tool: Tool,
        call: ContentBlock.ToolCall,
        arguments: JsonObject,
        scope: ApprovalScope,
        kind: DecisionKind,
    ): Outcome<RunCheckpoint> {
        val preview = if (kind == DecisionKind.APPROVAL) preview(checkpoint, tool, arguments) else null
        val pending = PendingDecision(call, tool.definition.id, scope, checkpoint.step, kind, preview)
        telemetry.event(
            TelemetryEvent.DECISION_REQUESTED,
            toolAttributes(checkpoint, tool) + Attributes.of { metadata(AttributeKeys.DECISION_KIND, kind.name) },
        )
        emit(RunEvent.DecisionRequested(pending, tool.definition, arguments, checkpoint.tainted))
        val waiting = when (kind) {
            DecisionKind.APPROVAL -> messages.awaitingUser(call.toolName)
            DecisionKind.QUESTION -> messages.questionShown(call.toolName)
        }
        val awaiting = ContentBlock.ToolResult(call.id, waiting, isError = false, provenance = Provenance.Trusted)
        return recordResult(checkpoint.copy(pendingDecisions = checkpoint.pendingDecisions + pending), awaiting)
    }

    /**
     * The tool's own account of a call it parks, kept with the call so the card and the approval agree even if the
     * app closes. A tool that fails to preview gets none, and never stops the run: its card shows the exact call.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun preview(checkpoint: RunCheckpoint, tool: Tool, arguments: JsonObject): CallPreview? = try {
        tool.preview(arguments)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        telemetry.event(TelemetryEvent.TOOL_FAILED, toolAttributes(checkpoint, tool))
        null
    }

    /** Applies what the user sent since the last step: instructions, then answers to decision cards. */
    private suspend fun FlowCollector<RunEvent>.applyInbox(start: RunCheckpoint, parent: SpanContext): Outcome<RunCheckpoint> {
        var checkpoint = start
        for (input in inbox.drain(start.runId)) {
            checkpoint = when (input) {
                is RunInput.Steer -> steer(checkpoint, input)
                is RunInput.Decision -> resolveDecision(checkpoint, input, parent)
                is RunInput.Answer -> resolveQuestion(checkpoint, input)
                is RunInput.CapabilityChanged -> capabilityChanged(checkpoint, input)
            }.getOrElse { return Outcome.Failure(it) }
        }
        return Outcome.Success(checkpoint)
    }

    private suspend fun steer(checkpoint: RunCheckpoint, input: RunInput.Steer): Outcome<RunCheckpoint> {
        telemetry.event(TelemetryEvent.RUN_STEERED, runAttributes(checkpoint))
        val blocks = listOf(ContentBlock.Text(input.text)) + input.pasted + input.images
        val tainted = checkpoint.tainted || Message(Role.USER, blocks).carriesUntrustedContent()
        return persist(checkpoint.copy(conversation = checkpoint.conversation.withUserContent(*blocks.toTypedArray()), tainted = tainted))
    }

    /**
     * Runs or drops a parked call. Only the exact call the user saw can run, and the harness runs it
     * itself, so the outcome never depends on the model repeating the call correctly. The outcome becomes the
     * call's own result in place of its "waiting" placeholder, as if the call had simply run (or been refused);
     * a placeholder the model has already seen stays, and the outcome follows as a note.
     */
    private suspend fun FlowCollector<RunEvent>.resolveDecision(
        checkpoint: RunCheckpoint,
        input: RunInput.Decision,
        parent: SpanContext,
    ): Outcome<RunCheckpoint> {
        // Unknown ids are stale or repeated taps; nothing to do.
        val pending = checkpoint.pendingDecisions.firstOrNull { it.toolCall.id == input.toolCallId && it.kind == DecisionKind.APPROVAL }
            ?: return Outcome.Success(checkpoint)
        val remaining = checkpoint.copy(pendingDecisions = checkpoint.pendingDecisions - pending)
        val call = pending.toolCall
        val tool = tools.find(pending.toolId)
        telemetry.event(
            TelemetryEvent.DECISION_ANSWERED,
            runAttributes(checkpoint) + Attributes.of {
                metadata(AttributeKeys.TOOL_ID, pending.toolId.value)
                metadata(AttributeKeys.DECISION_ANSWER, input.answer.name)
            },
        )
        if (input.answer == DecisionAnswer.DECLINED || tool == null) {
            val note = if (tool == null) messages.unknownTool(call.toolName) else messages.decisionDeclined(call.toolName)
            emit(RunEvent.DecisionResolved(pending, input.answer, output = null))
            return persist(remaining.settled(errorResult(call, note)) ?: remaining.copy(conversation = remaining.conversation.withUserContent(ContentBlock.Text(note))))
        }
        val approved = if (pending.scope == ApprovalScope.ONCE_PER_RUN) {
            remaining.copy(approvedOnceTools = remaining.approvedOnceTools + tool.definition.id)
        } else {
            remaining
        }
        val arguments = argumentParser.parse(call.argumentsJson, tool.definition.inputSchema).getOrElse {
            val note = messages.decisionFailed(call.toolName, it.message)
            emit(RunEvent.DecisionResolved(pending, input.answer, output = null))
            return persist(approved.settled(errorResult(call, note)) ?: approved.copy(conversation = approved.conversation.withUserContent(ContentBlock.Text(note))))
        }
        // Journal first: if the process dies mid-call, resume reports it instead of re-running it.
        val journaled = persist(approved.copy(inFlightDecision = call)).getOrElse { return Outcome.Failure(it) }
        val span = telemetry.startSpan(SpanName.TOOL_CALL, parent, toolAttributes(journaled, tool))
        // The tool may act only on what the approved card showed.
        val output = runTool(tool, call, arguments, journaled, approved = pending.preview?.approved)
        span.end(if (output.isError) SpanStatus.ERROR else SpanStatus.OK)
        if (output.isError) telemetry.event(TelemetryEvent.TOOL_FAILED, toolAttributes(journaled, tool))
        val provenance = output.provenance
        val missing = output.missingCapability
        if (missing != null) emit(RunEvent.CapabilityRequested(missing, call.id))
        val content = fitted(output.content, call, journaled)
        val result = if (missing != null) {
            errorResult(call, messages.capabilityMissing(call.toolName, missing.value))
        } else {
            ContentBlock.ToolResult(call.id, content, output.isError, provenance, personalData = !output.isError && tool.definition.exposesPersonalData)
        }
        emit(RunEvent.DecisionResolved(pending, input.answer, output))
        val after = journaled.copy(
            inFlightDecision = null,
            tainted = journaled.tainted || (missing == null && provenance is Provenance.Untrusted),
            personalDataRead = journaled.personalDataRead || result.personalData,
            requestedCapabilities = journaled.requestedCapabilities + listOfNotNull(missing),
        )
        val note = when {
            missing != null -> result.content
            output.isError -> messages.decisionFailed(call.toolName, content)
            provenance is Provenance.Untrusted -> messages.decisionApprovedUntrusted(call.toolName, provenance.sourceLabel, content)
            else -> messages.decisionApproved(call.toolName, content)
        }
        return persist(after.settled(result) ?: after.copy(conversation = after.conversation.withUserContent(ContentBlock.Text(note))))
    }

    private suspend fun FlowCollector<RunEvent>.resolveQuestion(checkpoint: RunCheckpoint, input: RunInput.Answer): Outcome<RunCheckpoint> {
        val pending = checkpoint.pendingDecisions.firstOrNull { it.toolCall.id == input.toolCallId && it.kind == DecisionKind.QUESTION }
            ?: return Outcome.Success(checkpoint)
        val tool = tools.find(pending.toolId) as? UserQuestionTool
        val arguments = tool?.let { argumentParser.parse(pending.toolCall.argumentsJson, it.definition.inputSchema).getOrNull() }
        val question = arguments?.let { tool.question(it) }.orEmpty()
        telemetry.event(
            TelemetryEvent.DECISION_ANSWERED,
            runAttributes(checkpoint) + Attributes.of {
                metadata(AttributeKeys.TOOL_ID, pending.toolId.value)
                metadata(AttributeKeys.DECISION_KIND, DecisionKind.QUESTION.name)
            },
        )
        emit(RunEvent.QuestionAnswered(pending, input.choice))
        val note = ContentBlock.Text(messages.questionAnswered(question, input.choice))
        return persist(
            checkpoint.copy(
                pendingDecisions = checkpoint.pendingDecisions - pending,
                conversation = checkpoint.conversation.withUserContent(note),
            ),
        )
    }

    private suspend fun capabilityChanged(checkpoint: RunCheckpoint, input: RunInput.CapabilityChanged): Outcome<RunCheckpoint> {
        if (input.capability !in checkpoint.requestedCapabilities) return Outcome.Success(checkpoint)
        val capability = input.capability.value
        val note = when {
            input.granted -> messages.capabilityGranted(capability)
            input.setupFailure != null -> messages.capabilitySetupFailed(capability, input.setupFailure)
            else -> messages.capabilityDenied(capability)
        }
        return persist(
            checkpoint.copy(
                requestedCapabilities = checkpoint.requestedCapabilities - input.capability,
                conversation = checkpoint.conversation.withUserContent(ContentBlock.Text(note)),
            ),
        )
    }

    /** On resume: an approved call that was running when the process died is reported, never re-run. */
    private suspend fun finishInterruptedDecision(checkpoint: RunCheckpoint): Outcome<RunCheckpoint> {
        val call = checkpoint.inFlightDecision ?: return Outcome.Success(checkpoint)
        val note = ContentBlock.Text(messages.interruptedToolCall(call.toolName))
        return persist(checkpoint.copy(inFlightDecision = null, conversation = checkpoint.conversation.withUserContent(note)))
    }

    /**
     * Puts [result] in place of its call's "waiting" placeholder, if the model hasn't seen that placeholder yet:
     * it is in the step still being gathered, or in the latest message (the run paused before the next model
     * call). Null when the model has already moved on past it.
     */
    private fun RunCheckpoint.settled(result: ContentBlock.ToolResult): RunCheckpoint? {
        val swap = { block: ContentBlock -> if (block is ContentBlock.ToolResult && block.toolCallId == result.toolCallId) result else block }
        if (stepResults.any { it.toolCallId == result.toolCallId }) {
            return copy(stepResults = stepResults.map { swap(it) as ContentBlock.ToolResult })
        }
        val last = conversation.messages.lastOrNull() ?: return null
        if (last.role != Role.USER || last.content.none { it is ContentBlock.ToolResult && it.toolCallId == result.toolCallId }) return null
        return copy(conversation = conversation.copy(messages = conversation.messages.dropLast(1) + last.copy(content = last.content.map(swap))))
    }

    /** Adds harness or user text to the latest user turn, keeping user and assistant turns alternating. */
    private fun Conversation.withUserContent(vararg blocks: ContentBlock): Conversation {
        val last = messages.lastOrNull()
        return if (last?.role == Role.USER) {
            copy(messages = messages.dropLast(1) + last.copy(content = last.content + blocks))
        } else {
            append(Message(Role.USER, blocks.toList()))
        }
    }

    private suspend fun FlowCollector<RunEvent>.execute(
        checkpoint: RunCheckpoint,
        tool: Tool,
        call: ContentBlock.ToolCall,
        arguments: JsonObject,
        parent: SpanContext,
    ): Outcome<RunCheckpoint> {
        // Journal the call first: if the process dies mid-call, resume reports it instead of re-running it.
        val journaled = persist(checkpoint.copy(inFlightToolCall = call)).getOrElse { return Outcome.Failure(it) }
        val span = telemetry.startSpan(SpanName.TOOL_CALL, parent, toolAttributes(checkpoint, tool))
        val output = runTool(tool, call, arguments, checkpoint)
        span.end(if (output.isError) SpanStatus.ERROR else SpanStatus.OK)
        if (output.isError) telemetry.event(TelemetryEvent.TOOL_FAILED, toolAttributes(checkpoint, tool))
        val missing = output.missingCapability
        if (missing != null) {
            // The user is asked with a card; the model hears that from the harness, not from the tool.
            emit(RunEvent.CapabilityRequested(missing, call.id))
            val result = errorResult(call, messages.capabilityMissing(call.toolName, missing.value))
            return recordResult(journaled.copy(inFlightToolCall = null, requestedCapabilities = journaled.requestedCapabilities + missing), result)
        }
        if (tool is PlanTool && !output.isError) {
            PlanTool.parse(arguments)?.let { plan -> return recordResult(journaled.copy(inFlightToolCall = null, plan = plan), planResult(call, output)) }
        }
        val result = ContentBlock.ToolResult(
            toolCallId = call.id,
            content = fitted(output.content, call, checkpoint),
            isError = output.isError,
            provenance = output.provenance,
            personalData = !output.isError && tool.definition.exposesPersonalData,
        )
        return recordResult(journaled.copy(inFlightToolCall = null), result, fullContent = output.content)
    }

    private fun planResult(call: ContentBlock.ToolCall, output: ToolOutput) =
        ContentBlock.ToolResult(call.id, output.content, isError = false, provenance = output.provenance)

    /** The checkpoint to continue with when the model stopped with plan steps open; null to finish normally. */
    private fun remindOfPlan(checkpoint: RunCheckpoint): RunCheckpoint? {
        if (checkpoint.planReminded || checkpoint.pendingDecisions.isNotEmpty()) return null
        val open = checkpoint.plan.orEmpty().filter { it.status != PlanStatus.COMPLETED }
        if (open.isEmpty()) return null
        val note = messages.planUnfinished(open.joinToString("\n") { "- " + it.content })
        return checkpoint.copy(planReminded = true, conversation = checkpoint.conversation.withUserContent(ContentBlock.Text(note)))
    }

    /** Tools are a trust boundary: any failure becomes an error result for the model, never a crash. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runTool(
        tool: Tool,
        call: ContentBlock.ToolCall,
        arguments: JsonObject,
        checkpoint: RunCheckpoint,
        approved: List<String>? = null,
    ): ToolOutput = try {
        tool.execute(arguments, ToolExecutionContext(checkpoint.runId, call.id, checkpoint.workspace, approved))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        ToolOutput(
            content = messages.toolFailed(call.toolName, exception.message ?: exception.javaClass.simpleName),
            isError = true,
            provenance = Provenance.Trusted,
        )
    }

    private suspend fun FlowCollector<RunEvent>.recordResult(
        checkpoint: RunCheckpoint,
        result: ContentBlock.ToolResult,
        fullContent: String = result.content,
    ): Outcome<RunCheckpoint> {
        emit(RunEvent.ToolCallFinished(result, fullContent))
        return persist(
            checkpoint.copy(
                stepResults = checkpoint.stepResults + result,
                tainted = checkpoint.tainted || result.provenance is Provenance.Untrusted,
                personalDataRead = checkpoint.personalDataRead || result.personalData,
            ),
        )
    }

    private fun errorResult(call: ContentBlock.ToolCall, content: String) =
        ContentBlock.ToolResult(call.id, content, isError = true, provenance = Provenance.Trusted)

    /**
     * Output short enough goes to the model as it is. Longer output is saved in the chat's workspace and the
     * model gets its start and end with the path; with nowhere to save it, it's cut.
     */
    private suspend fun fitted(content: String, call: ContentBlock.ToolCall, checkpoint: RunCheckpoint): String {
        val policy = limits.toolOutput
        if (content.length <= policy.saveAboveChars) return truncate(content)
        val workspace = checkpoint.workspace
        val path = if (workspace != null) outputs?.save(workspace, checkpoint.runId, call.id, content)?.getOrNull() else null
        if (path == null) return truncate(content)
        telemetry.event(
            TelemetryEvent.TOOL_OUTPUT_SAVED,
            runAttributes(checkpoint) + Attributes.of {
                metadata(AttributeKeys.TOOL_ID, call.toolName)
                number(AttributeKeys.OUTPUT_CHARS, content.length)
            },
        )
        return messages.outputSaved(content.take(policy.previewHeadChars), content.takeLast(policy.previewTailChars), content.length, path)
    }

    private fun truncate(content: String): String =
        if (content.length <= limits.maxToolOutputChars) {
            content
        } else {
            content.take(limits.maxToolOutputChars) + "\n" + messages.outputTruncated(limits.maxToolOutputChars)
        }

    private suspend fun persist(checkpoint: RunCheckpoint): Outcome<RunCheckpoint> =
        checkpoints.save(checkpoint).map { checkpoint }

    private suspend fun FlowCollector<RunEvent>.complete(checkpoint: RunCheckpoint, finalMessage: Message): RunCheckpoint {
        val completed = checkpoint.copy(status = RunStatus.COMPLETED)
        persist(completed).getOrElse { return fail(checkpoint, it) }
        telemetry.event(
            TelemetryEvent.RUN_COMPLETED,
            runAttributes(completed) + Attributes.of { number(AttributeKeys.OPEN_DECISIONS, completed.pendingDecisions.size) },
        )
        emit(RunEvent.Completed(finalMessage, completed.usage, completed.pendingDecisions))
        return completed
    }

    private suspend fun FlowCollector<RunEvent>.pause(checkpoint: RunCheckpoint, reason: PauseReason): RunCheckpoint {
        val paused = checkpoint.copy(status = RunStatus.PAUSED, pauseReason = reason)
        persist(paused).getOrElse { return fail(checkpoint, it) }
        telemetry.event(TelemetryEvent.RUN_PAUSED, runAttributes(paused))
        emit(RunEvent.Paused(reason, paused.usage))
        return paused
    }

    /** Best effort: if even the failure can't be saved, the run is still reported as failed. */
    private suspend fun FlowCollector<RunEvent>.fail(checkpoint: RunCheckpoint, error: HarnessError): RunCheckpoint {
        val failed = checkpoint.copy(status = RunStatus.FAILED)
        checkpoints.save(failed)
        telemetry.event(
            TelemetryEvent.RUN_FAILED,
            runAttributes(failed) + Attributes.of {
                metadata(AttributeKeys.ERROR_TYPE, error::class.simpleName)
                content(AttributeKeys.ERROR_MESSAGE, error.message)
            },
        )
        emit(RunEvent.Failed(error))
        return failed
    }

    private fun runAttributes(checkpoint: RunCheckpoint) = Attributes.of {
        metadata(AttributeKeys.RUN_ID, checkpoint.runId.value)
        metadata(AttributeKeys.RUN_MODE, checkpoint.mode.name)
        metadata(AttributeKeys.MODEL_ID, checkpoint.model.id.value)
        metadata(AttributeKeys.PROVIDER_ID, checkpoint.model.providerId.value)
        metadata(AttributeKeys.MODEL_TRUST_TIER, checkpoint.model.trustTier.name)
        metadata(AttributeKeys.RUN_STATUS, checkpoint.status.name)
        number(AttributeKeys.STEP, checkpoint.step)
        number(AttributeKeys.TOTAL_TOKENS, checkpoint.usage.totalTokens)
    }

    private fun stepAttributes(checkpoint: RunCheckpoint) = Attributes.of {
        metadata(AttributeKeys.RUN_ID, checkpoint.runId.value)
        number(AttributeKeys.STEP, checkpoint.step)
        flag(AttributeKeys.TAINTED, checkpoint.tainted)
    }

    private fun toolAttributes(checkpoint: RunCheckpoint, tool: Tool) = Attributes.of {
        metadata(AttributeKeys.RUN_ID, checkpoint.runId.value)
        metadata(AttributeKeys.TOOL_ID, tool.definition.id.value)
        metadata(AttributeKeys.RISK_TIER, tool.definition.riskTier.name)
        metadata(AttributeKeys.EXECUTION_SITE, tool.definition.executionSite.name)
    }

    private fun hostedToolAttributes(checkpoint: RunCheckpoint, event: ModelEvent.HostedToolFinished) = Attributes.of {
        metadata(AttributeKeys.RUN_ID, checkpoint.runId.value)
        metadata(AttributeKeys.TOOL_ID, event.tool.toolName)
        metadata(AttributeKeys.EXECUTION_SITE, HostedToolDefinitions.of(event.tool).executionSite.name)
    }

    private fun decisionAttributes(checkpoint: RunCheckpoint, tool: Tool, decision: PolicyDecision) =
        toolAttributes(checkpoint, tool) + Attributes.of {
            metadata(AttributeKeys.DECISION, decision::class.simpleName)
            metadata(AttributeKeys.DECISION_REASON, decision.reason::class.simpleName)
            flag(AttributeKeys.TAINTED, checkpoint.tainted)
        }

    /** Failed runs are final; completed runs only continue when the user answers an open card. */
    private fun RunCheckpoint.canContinue(): Boolean = when (status) {
        RunStatus.FAILED -> false
        RunStatus.COMPLETED -> (pendingDecisions.isNotEmpty() || requestedCapabilities.isNotEmpty()) && inbox.hasInput(runId)
        RunStatus.RUNNING, RunStatus.PAUSED -> true
    }

    /** One model call's result, and whether any of it already reached the user (then it can't be retried). */
    private class ModelTurn(val assembler: AssistantTurnAssembler, val shownToUser: Boolean)

    private class ModelConnection(
        val adapter: ModelAdapter,
        val provider: ProviderDescriptor,
        val apiKey: Secret,
    )
}
