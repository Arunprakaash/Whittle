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
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolArgumentParser
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import app.whittle.tools.api.ToolRegistry
import app.whittle.tools.api.UserQuestionTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * The agent loop: call the model, check every proposed tool call against the policy engine, run the
 * tool, and repeat. Calls that need the user's choice never block the run: they are parked as
 * [PendingDecision]s and the model carries on with independent work. The user's answers and any
 * mid-run instructions arrive through [RunInbox] and are applied at the next step boundary.
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
    private val argumentParser: ToolArgumentParser = ToolArgumentParser(),
) {
    fun start(request: RunRequest): Flow<RunEvent> = flow {
        val prompt = systemPrompt.render(request.promptVariables).getOrElse {
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
            // Earlier turns of the chat are still in front of the model, and so is what they brought in.
            tainted = request.priorMessages.any(Message::carriesUntrustedContent),
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

            val turn = callModel(checkpoint, connection, stepSpan.context)
            turn.failure?.let {
                stepSpan.end(SpanStatus.ERROR)
                return fail(checkpoint, it)
            }
            val assistant = turn.toMessage()
            checkpoint = persist(
                checkpoint.copy(
                    conversation = checkpoint.conversation.append(assistant),
                    usage = checkpoint.usage + turn.usage,
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

    private fun pauseReasonFor(checkpoint: RunCheckpoint): PauseReason? =
        PauseReason.STEP_LIMIT.takeIf { checkpoint.step >= limits.maxStepsPerRun }

    private suspend fun FlowCollector<RunEvent>.callModel(
        checkpoint: RunCheckpoint,
        connection: ModelConnection,
        parent: SpanContext,
    ): AssistantTurnAssembler {
        val request = ModelRequest(
            model = checkpoint.model,
            systemPrompt = checkpoint.conversation.systemPrompt,
            messages = checkpoint.conversation.messages,
            tools = tools.definitions.map { ToolSpec(it.id.value, it.description, it.inputSchema) },
            maxOutputTokens = limits.maxOutputTokensPerStep,
            reasoningEffort = checkpoint.reasoningEffort,
            hostedTools = allowedHostedTools(checkpoint, connection.provider),
        )
        val assembler = AssistantTurnAssembler()
        val startedAt = clock.nowMillis()
        connection.adapter.stream(request, connection.provider, connection.apiKey).collect { event ->
            assembler.accept(event)
            when (event) {
                is ModelEvent.TextDelta -> emit(RunEvent.AssistantTextDelta(event.text))
                is ModelEvent.HostedToolStarted -> emit(RunEvent.HostedToolStarted(event.callId, event.tool))
                is ModelEvent.SourceCited -> emit(RunEvent.SourceCited(event.url, event.title))
                is ModelEvent.HostedToolFinished -> {
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
        return assembler
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

    /** Parks a call or question for the user and tells the model it is waiting. The run does not block. */
    private suspend fun FlowCollector<RunEvent>.park(
        checkpoint: RunCheckpoint,
        tool: Tool,
        call: ContentBlock.ToolCall,
        arguments: JsonObject,
        scope: ApprovalScope,
        kind: DecisionKind,
    ): Outcome<RunCheckpoint> {
        val pending = PendingDecision(call, tool.definition.id, scope, checkpoint.step, kind)
        telemetry.event(
            TelemetryEvent.DECISION_REQUESTED,
            toolAttributes(checkpoint, tool) + Attributes.of { metadata(AttributeKeys.DECISION_KIND, kind.name) },
        )
        emit(RunEvent.DecisionRequested(pending, tool.definition, arguments))
        val waiting = when (kind) {
            DecisionKind.APPROVAL -> messages.awaitingUser(call.toolName)
            DecisionKind.QUESTION -> messages.questionShown(call.toolName)
        }
        val awaiting = ContentBlock.ToolResult(call.id, waiting, isError = false, provenance = Provenance.Trusted)
        return recordResult(checkpoint.copy(pendingDecisions = checkpoint.pendingDecisions + pending), awaiting)
    }

    /** Applies what the user sent since the last step: instructions, then answers to decision cards. */
    private suspend fun FlowCollector<RunEvent>.applyInbox(start: RunCheckpoint, parent: SpanContext): Outcome<RunCheckpoint> {
        var checkpoint = start
        for (input in inbox.drain(start.runId)) {
            checkpoint = when (input) {
                is RunInput.Steer -> steer(checkpoint, input.text)
                is RunInput.Decision -> resolveDecision(checkpoint, input, parent)
                is RunInput.Answer -> resolveQuestion(checkpoint, input)
                is RunInput.CapabilityChanged -> capabilityChanged(checkpoint, input)
            }.getOrElse { return Outcome.Failure(it) }
        }
        return Outcome.Success(checkpoint)
    }

    private suspend fun steer(checkpoint: RunCheckpoint, text: String): Outcome<RunCheckpoint> {
        telemetry.event(TelemetryEvent.RUN_STEERED, runAttributes(checkpoint))
        return persist(checkpoint.copy(conversation = checkpoint.conversation.withUserContent(ContentBlock.Text(text))))
    }

    /**
     * Runs or drops a parked call. Only the exact call the user saw can run, and the harness runs it
     * itself, so the outcome never depends on the model repeating the call correctly.
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
            return persist(remaining.copy(conversation = remaining.conversation.withUserContent(ContentBlock.Text(note))))
        }
        val approved = if (pending.scope == ApprovalScope.ONCE_PER_RUN) {
            remaining.copy(approvedOnceTools = remaining.approvedOnceTools + tool.definition.id)
        } else {
            remaining
        }
        val arguments = argumentParser.parse(call.argumentsJson, tool.definition.inputSchema).getOrElse {
            val note = messages.decisionFailed(call.toolName, it.message)
            emit(RunEvent.DecisionResolved(pending, input.answer, output = null))
            return persist(approved.copy(conversation = approved.conversation.withUserContent(ContentBlock.Text(note))))
        }
        // Journal first: if the process dies mid-call, resume reports it instead of re-running it.
        val journaled = persist(approved.copy(inFlightDecision = call)).getOrElse { return Outcome.Failure(it) }
        val span = telemetry.startSpan(SpanName.TOOL_CALL, parent, toolAttributes(journaled, tool))
        val output = runTool(tool, call, arguments, journaled)
        span.end(if (output.isError) SpanStatus.ERROR else SpanStatus.OK)
        if (output.isError) telemetry.event(TelemetryEvent.TOOL_FAILED, toolAttributes(journaled, tool))
        val provenance = output.provenance
        val missing = output.missingCapability
        if (missing != null) emit(RunEvent.CapabilityRequested(missing, call.id))
        val note = when {
            missing != null -> messages.capabilityMissing(call.toolName, missing.value)
            output.isError -> messages.decisionFailed(call.toolName, truncate(output.content))
            provenance is Provenance.Untrusted -> messages.decisionApprovedUntrusted(call.toolName, provenance.sourceLabel, truncate(output.content))
            else -> messages.decisionApproved(call.toolName, truncate(output.content))
        }
        emit(RunEvent.DecisionResolved(pending, input.answer, output))
        return persist(
            journaled.copy(
                inFlightDecision = null,
                conversation = journaled.conversation.withUserContent(ContentBlock.Text(note)),
                tainted = journaled.tainted || (missing == null && provenance is Provenance.Untrusted),
                personalDataRead = journaled.personalDataRead || (missing == null && !output.isError && tool.definition.exposesPersonalData),
                requestedCapabilities = journaled.requestedCapabilities + listOfNotNull(missing),
            ),
        )
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
        val note = if (input.granted) messages.capabilityGranted(capability) else messages.capabilityDenied(capability)
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

    /** Adds harness or user text to the latest user turn, keeping user and assistant turns alternating. */
    private fun Conversation.withUserContent(block: ContentBlock): Conversation {
        val last = messages.lastOrNull()
        return if (last?.role == Role.USER) {
            copy(messages = messages.dropLast(1) + last.copy(content = last.content + block))
        } else {
            append(Message(Role.USER, listOf(block)))
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
            content = truncate(output.content),
            isError = output.isError,
            provenance = output.provenance,
            personalData = !output.isError && tool.definition.exposesPersonalData,
        )
        return recordResult(journaled.copy(inFlightToolCall = null), result)
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
    private suspend fun runTool(tool: Tool, call: ContentBlock.ToolCall, arguments: JsonObject, checkpoint: RunCheckpoint): ToolOutput = try {
        tool.execute(arguments, ToolExecutionContext(checkpoint.runId, call.id, checkpoint.workspace))
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
    ): Outcome<RunCheckpoint> {
        emit(RunEvent.ToolCallFinished(result))
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

    private class ModelConnection(
        val adapter: ModelAdapter,
        val provider: ProviderDescriptor,
        val apiKey: Secret,
    )
}
