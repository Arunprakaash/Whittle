package com.mobileagent.agent

import com.mobileagent.core.common.EpochClock
import com.mobileagent.core.common.HarnessError
import com.mobileagent.core.common.Outcome
import com.mobileagent.core.common.Secret
import com.mobileagent.core.common.flatMap
import com.mobileagent.core.common.getOrElse
import com.mobileagent.core.common.map
import com.mobileagent.core.config.AgentLimits
import com.mobileagent.core.config.PromptTemplate
import com.mobileagent.core.model.ContentBlock
import com.mobileagent.core.model.Conversation
import com.mobileagent.core.model.Message
import com.mobileagent.core.model.Provenance
import com.mobileagent.core.model.Role
import com.mobileagent.core.model.RunId
import com.mobileagent.core.model.TokenUsage
import com.mobileagent.llm.api.AssistantTurnAssembler
import com.mobileagent.llm.api.CredentialsProvider
import com.mobileagent.llm.api.ModelAdapter
import com.mobileagent.llm.api.ModelAdapterRegistry
import com.mobileagent.llm.api.ModelDescriptor
import com.mobileagent.llm.api.ModelEvent
import com.mobileagent.llm.api.ModelRequest
import com.mobileagent.llm.api.ProviderCatalog
import com.mobileagent.llm.api.ProviderDescriptor
import com.mobileagent.llm.api.ToolSpec
import com.mobileagent.observability.AttributeKeys
import com.mobileagent.observability.Attributes
import com.mobileagent.observability.GenerationRecord
import com.mobileagent.observability.SpanContext
import com.mobileagent.observability.SpanName
import com.mobileagent.observability.SpanStatus
import com.mobileagent.observability.Telemetry
import com.mobileagent.observability.TelemetryEvent
import com.mobileagent.policy.ApprovalScope
import com.mobileagent.policy.PolicyDecision
import com.mobileagent.policy.PolicyEngine
import com.mobileagent.tools.api.Tool
import com.mobileagent.tools.api.ToolArgumentParser
import com.mobileagent.tools.api.ToolExecutionContext
import com.mobileagent.tools.api.ToolId
import com.mobileagent.tools.api.ToolOutput
import com.mobileagent.tools.api.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * The agent loop: call the model, check every proposed tool call against the policy engine, ask the
 * user when required, run the tool, and repeat. A checkpoint is saved after every step and before every
 * tool call, so [resume] can continue after process death without repeating a side effect.
 */
class AgentRunner(
    private val adapters: ModelAdapterRegistry,
    private val providers: ProviderCatalog,
    private val credentials: CredentialsProvider,
    private val tools: ToolRegistry,
    private val policy: PolicyEngine,
    private val approvals: ApprovalGateway,
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
            conversation = Conversation(prompt, listOf(request.userMessage)),
            tainted = false,
            preApprovedTools = request.preApprovedTools,
            approvedOnceTools = emptySet(),
            usage = TokenUsage.Zero,
            tokenBudget = limits.tokenBudgetPerRun,
            status = RunStatus.RUNNING,
        )
        telemetry.event(TelemetryEvent.RUN_STARTED, runAttributes(checkpoint))
        drive(checkpoint)
    }

    /** Continues a paused or interrupted run. [extraTokenBudget] lets the user allow more tokens. */
    fun resume(runId: RunId, extraTokenBudget: Long = 0): Flow<RunEvent> = flow {
        val checkpoint = checkpoints.load(runId).getOrElse {
            emit(RunEvent.Failed(it))
            return@flow
        }
        if (checkpoint == null || checkpoint.status == RunStatus.COMPLETED || checkpoint.status == RunStatus.FAILED) {
            emit(RunEvent.Failed(HarnessError.NotFound("No resumable run ${runId.value}")))
            return@flow
        }
        val resumed = checkpoint.copy(
            status = RunStatus.RUNNING,
            pauseReason = null,
            tokenBudget = checkpoint.tokenBudget + extraTokenBudget,
        )
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
        var checkpoint = finishInterruptedStep(initial, runSpan).getOrElse { return fail(initial, it) }
        while (true) {
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
                checkpoint.copy(conversation = checkpoint.conversation.append(assistant), usage = checkpoint.usage + turn.usage),
            ).getOrElse {
                stepSpan.end(SpanStatus.ERROR)
                return fail(checkpoint, it)
            }
            if (assistant.toolCalls.isEmpty()) {
                stepSpan.end(SpanStatus.OK)
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
        checkpoint.usage.totalTokens >= checkpoint.tokenBudget -> PauseReason.TOKEN_BUDGET
        checkpoint.step >= limits.maxStepsPerRun -> PauseReason.STEP_LIMIT
        else -> null
    }

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
        )
        val assembler = AssistantTurnAssembler()
        val startedAt = clock.nowMillis()
        connection.adapter.stream(request, connection.provider, connection.apiKey).collect { event ->
            assembler.accept(event)
            if (event is ModelEvent.TextDelta) emit(RunEvent.AssistantTextDelta(event.text))
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
        val decision = policy.evaluate(tool.definition, checkpoint.runContext())
        telemetry.event(TelemetryEvent.POLICY_DECISION, decisionAttributes(checkpoint, tool, decision))
        emit(RunEvent.ToolCallDecided(call, decision))

        return when (decision) {
            is PolicyDecision.Deny -> recordResult(checkpoint, errorResult(call, messages.policyDenied(call.toolName)))
            is PolicyDecision.Allow -> execute(checkpoint, tool, call, arguments, parent)
            is PolicyDecision.Ask -> {
                val answer = approvals.request(
                    ApprovalRequest(checkpoint.runId, call.id, tool.definition, arguments, decision.reason, decision.scope, checkpoint.mode),
                )
                telemetry.event(
                    TelemetryEvent.APPROVAL_ANSWERED,
                    toolAttributes(checkpoint, tool) + Attributes.of { metadata(AttributeKeys.APPROVAL_ANSWER, answer.name) },
                )
                when (answer) {
                    ApprovalAnswer.DECLINED -> recordResult(checkpoint, errorResult(call, messages.userDeclined(call.toolName)))
                    ApprovalAnswer.APPROVED -> {
                        val approved = if (decision.scope == ApprovalScope.ONCE_PER_RUN) {
                            checkpoint.copy(approvedOnceTools = checkpoint.approvedOnceTools + tool.definition.id)
                        } else {
                            checkpoint
                        }
                        execute(approved, tool, call, arguments, parent)
                    }
                }
            }
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
        val output = runTool(tool, call, arguments, checkpoint.runId)
        span.end(if (output.isError) SpanStatus.ERROR else SpanStatus.OK)
        if (output.isError) telemetry.event(TelemetryEvent.TOOL_FAILED, toolAttributes(checkpoint, tool))
        val result = ContentBlock.ToolResult(call.id, truncate(output.content), output.isError, output.provenance)
        return recordResult(journaled.copy(inFlightToolCall = null), result)
    }

    /** Tools are a trust boundary: any failure becomes an error result for the model, never a crash. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runTool(tool: Tool, call: ContentBlock.ToolCall, arguments: JsonObject, runId: RunId): ToolOutput = try {
        tool.execute(arguments, ToolExecutionContext(runId, call.id))
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
        telemetry.event(TelemetryEvent.RUN_COMPLETED, runAttributes(completed))
        emit(RunEvent.Completed(finalMessage, completed.usage))
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

    private fun decisionAttributes(checkpoint: RunCheckpoint, tool: Tool, decision: PolicyDecision) =
        toolAttributes(checkpoint, tool) + Attributes.of {
            metadata(AttributeKeys.DECISION, decision::class.simpleName)
            metadata(AttributeKeys.DECISION_REASON, decision.reason::class.simpleName)
            flag(AttributeKeys.TAINTED, checkpoint.tainted)
        }

    private class ModelConnection(
        val adapter: ModelAdapter,
        val provider: ProviderDescriptor,
        val apiKey: Secret,
    )
}
