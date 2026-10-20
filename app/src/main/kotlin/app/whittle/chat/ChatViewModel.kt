package app.whittle.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.whittle.AppGraph
import app.whittle.agent.DecisionAnswer
import app.whittle.agent.DecisionKind
import app.whittle.agent.PauseReason
import app.whittle.agent.RunEvent
import app.whittle.agent.RunInput
import app.whittle.agent.RunRequest
import app.whittle.agent.SystemPromptVariables
import app.whittle.core.common.HarnessError
import app.whittle.core.common.getOrNull
import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.design.components.AgentState
import app.whittle.design.components.DecisionUi
import app.whittle.design.components.QuestionUi
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.llm.api.ModelDescriptor
import app.whittle.policy.PolicyDecision
import app.whittle.policy.RunMode
import app.whittle.tools.presentation.StepText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.util.Locale

data class ChatState(
    val model: ModelDescriptor? = null,
    val items: List<ThreadItem> = emptyList(),
    val input: String = "",
    val status: StatusUi? = null,
) {
    val running: Boolean get() = status != null
}

/** The pinned status line. A null [verb] means the agent is thinking between actions. */
data class StatusUi(val state: AgentState, val verb: String?, val startedAtMillis: Long)

/**
 * Turns the harness's run events into the thread the user reads: narration, grouped step rows and
 * inline decision cards. Decisions never hold a run; answering one later continues that run.
 */
class ChatViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _haptics = MutableSharedFlow<WhittleHaptic>(extraBufferCapacity = HAPTIC_BUFFER)
    val haptics: SharedFlow<WhittleHaptic> = _haptics.asSharedFlow()

    private var activeJob: Job? = null
    private var activeRunId: RunId? = null
    private val waitingToContinue = ArrayDeque<RunId>()
    private var history: List<Message> = emptyList()
    private var segment = Segment(start = 0, startedAtMillis = 0)
    private val stepTexts = mutableMapOf<String, StepText>()
    private val parkedCalls = mutableSetOf<String>()
    private val needsAccess = mutableSetOf<String>()
    private var nextKey = 0L

    init {
        viewModelScope.launch { graph.connections.model.collect { model -> _state.update { it.copy(model = model) } } }
    }

    fun updateInput(text: String) = _state.update { it.copy(input = text) }

    /** Starts a task, or adds to the one that's running without stopping it. */
    fun send() {
        val text = _state.value.input.trim()
        val model = _state.value.model ?: return
        if (text.isEmpty()) return
        _state.update { it.copy(input = "") }
        val running = activeRunId
        if (activeJob?.isActive == true && running != null) {
            graph.inbox.submit(running, RunInput.Steer(text))
            append(ThreadItem.User(key(), text, steer = true))
            return
        }
        val runId = RunId(graph.idGenerator.newId())
        append(ThreadItem.User(key(), text, steer = false))
        val request = RunRequest(
            runId = runId,
            model = model,
            userMessage = Message.userText(text),
            priorMessages = history,
            mode = RunMode.LIVE,
            reasoningEffort = graph.modelDefaults.defaultReasoningEffort,
            preApprovedTools = emptySet(),
            promptVariables = mapOf(
                SystemPromptVariables.CURRENT_DATE to LocalDate.now().toString(),
                SystemPromptVariables.USER_LOCALE to Locale.getDefault().toLanguageTag(),
            ),
        )
        collect(runId, graph.runner.start(request))
    }

    /** The user's choice on a card. Applied at the run's next step, or continues a finished run. */
    fun answer(item: ThreadItem.Decision, approve: Boolean) {
        replace(item.key) {
            ThreadItem.Resolved(item.key, if (approve) item.approvedOutcome else item.declinedOutcome, declined = !approve)
        }
        val answer = if (approve) DecisionAnswer.APPROVED else DecisionAnswer.DECLINED
        submit(item.runId, RunInput.Decision(item.toolCallId, answer))
    }

    fun answer(item: ThreadItem.Question, choice: String) {
        replace(item.key) { ThreadItem.Resolved(item.key, choice, declined = false) }
        submit(item.runId, RunInput.Answer(item.toolCallId, choice))
    }

    /** Called with the system permission result. A refusal only reaches a run that is still working. */
    fun answer(item: ThreadItem.Capability, granted: Boolean) {
        replace(item.key) { ThreadItem.Resolved(item.key, if (granted) item.grantedOutcome else item.deniedOutcome, declined = !granted) }
        val input = RunInput.CapabilityChanged(item.capability, granted)
        if (granted || (activeJob?.isActive == true && activeRunId == item.runId)) submit(item.runId, input)
    }

    private fun submit(runId: RunId, input: RunInput) {
        graph.inbox.submit(runId, input)
        when {
            activeJob?.isActive != true -> collect(runId, graph.runner.resume(runId))
            activeRunId != runId && runId !in waitingToContinue -> waitingToContinue.addLast(runId)
        }
    }

    fun stop() {
        activeJob?.cancel()
        activeJob = null
        finishNarration()
        _state.update { state ->
            state.copy(
                status = null,
                items = state.items.map { item ->
                    if (item is ThreadItem.Steps) item.copy(steps = item.steps.map { if (it.status == StepState.Running) it.copy(status = StepState.Stopped) else it }) else item
                } + ThreadItem.Notice(key(), NoticeKind.Stopped),
            )
        }
    }

    fun toggleSummary(key: String) = replace(key) { (it as ThreadItem.Summary).let { summary -> summary.copy(expanded = !summary.expanded) } }

    fun newTask() {
        activeJob?.cancel()
        activeJob = null
        activeRunId = null
        waitingToContinue.clear()
        history = emptyList()
        _state.update { it.copy(items = emptyList(), status = null, input = "") }
    }

    fun changeModel() {
        newTask()
        viewModelScope.launch { graph.connections.clear() }
    }

    private fun collect(runId: RunId, events: Flow<RunEvent>) {
        activeRunId = runId
        segment = Segment(start = _state.value.items.size, startedAtMillis = graph.clock.nowMillis())
        _state.update { it.copy(status = StatusUi(AgentState.Thinking, verb = null, startedAtMillis = segment.startedAtMillis)) }
        activeJob = viewModelScope.launch {
            // The loop encrypts and saves a checkpoint every step, so it runs off the main thread.
            events.flowOn(Dispatchers.Default).collect { event ->
                onEvent(event)
                // The next message continues this conversation, including any tool results.
                if (event is RunEvent.Completed) graph.checkpoints.load(runId).getOrNull()?.let { history = it.conversation.messages }
            }
            activeJob = null
            waitingToContinue.removeFirstOrNull()?.let { next -> collect(next, graph.runner.resume(next)) }
        }
    }

    private fun onEvent(event: RunEvent) {
        // Text streams until anything else happens; then that narration segment is complete.
        if (event !is RunEvent.AssistantTextDelta) finishNarration()
        when (event) {
            is RunEvent.StepStarted -> {
                finishNarration()
                setStatus(AgentState.Thinking, verb = null)
            }
            is RunEvent.AssistantTextDelta -> appendNarration(event.text)
            is RunEvent.ToolCallDecided -> onToolCallDecided(event)
            is RunEvent.ToolCallFinished -> onToolCallFinished(event)
            is RunEvent.DecisionRequested -> onDecisionRequested(event)
            is RunEvent.DecisionResolved, is RunEvent.QuestionAnswered -> Unit // The card already shows the user's choice.
            is RunEvent.CapabilityRequested -> onCapabilityRequested(event)
            is RunEvent.Completed -> onCompleted()
            is RunEvent.Paused -> end(if (event.reason == PauseReason.TOKEN_BUDGET) NoticeKind.PausedBudget else NoticeKind.PausedSteps)
            is RunEvent.Failed -> end(event.error.toNotice(), statusCode = (event.error as? HarnessError.Provider)?.statusCode)
        }
    }

    private fun onToolCallDecided(event: RunEvent.ToolCallDecided) {
        finishNarration()
        val call = event.toolCall
        val text = graph.presenter.step(call.toolName, arguments(call.argumentsJson))
        stepTexts[call.id.value] = text
        when (event.decision) {
            is PolicyDecision.Allow -> {
                addStep(StepModel(call.id.value, text.icon, text.running, StepState.Running))
                setStatus(AgentState.Acting, text.running)
            }
            is PolicyDecision.Deny -> addStep(StepModel(call.id.value, text.icon, text.running, StepState.Blocked))
            is PolicyDecision.Ask -> parkedCalls += call.id.value
        }
    }

    private fun onToolCallFinished(event: RunEvent.ToolCallFinished) {
        val id = event.result.toolCallId.value
        if (id in parkedCalls) return
        val text = stepTexts[id]
        updateStep(id) { step ->
            when {
                step.status == StepState.Blocked -> step
                id in needsAccess -> step.copy(status = StepState.NeedsAccess)
                else -> step.copy(label = text?.done ?: step.label, status = if (event.result.isError) StepState.Failed else StepState.Done)
            }
        }
        if (!event.result.isError) _haptics.tryEmit(WhittleHaptic.StepDone)
    }

    private fun onDecisionRequested(event: RunEvent.DecisionRequested) {
        if (event.decision.kind == DecisionKind.QUESTION) return onQuestionRequested(event)
        val call = event.decision.toolCall
        val text = graph.presenter.decision(call.toolName, event.arguments)
        val runId = activeRunId ?: return
        append(
            ThreadItem.Decision(
                key = "decision-${call.id.value}",
                runId = runId,
                toolCallId = call.id,
                ui = DecisionUi(
                    id = call.id.value,
                    icon = text.icon,
                    title = text.title,
                    body = text.body,
                    payloadLabel = text.payloadLabel,
                    payload = text.payload,
                    approveLabel = text.approve,
                    approveIcon = text.approveIcon,
                    declineLabel = text.decline,
                ),
                approvedOutcome = text.approvedOutcome,
                declinedOutcome = text.declinedOutcome,
            ),
        )
        _haptics.tryEmit(WhittleHaptic.DecisionShown)
    }

    private fun onQuestionRequested(event: RunEvent.DecisionRequested) {
        val call = event.decision.toolCall
        val text = graph.presenter.question(call.toolName, event.arguments) ?: return
        val runId = activeRunId ?: return
        append(ThreadItem.Question("question-${call.id.value}", runId, call.id, QuestionUi(call.id.value, text.icon, text.question, text.options)))
        _haptics.tryEmit(WhittleHaptic.DecisionShown)
    }

    private fun onCapabilityRequested(event: RunEvent.CapabilityRequested) {
        needsAccess += event.toolCallId.value
        val capability = event.capability
        val presentation = graph.presenter.capability(capability.value) ?: return
        val runId = activeRunId ?: return
        // One card per capability: several tools may need the same access in one run.
        if (_state.value.items.any { it is ThreadItem.Capability && it.capability == capability }) return
        append(
            ThreadItem.Capability(
                key = "capability-${capability.value}-${key()}",
                runId = runId,
                capability = capability,
                ui = DecisionUi(
                    id = capability.value,
                    icon = presentation.icon,
                    title = presentation.title,
                    body = presentation.body,
                    payloadLabel = null,
                    payload = null,
                    approveLabel = presentation.approve,
                    approveIcon = CAPABILITY_APPROVE_ICON,
                    declineLabel = presentation.decline,
                ),
                grantedOutcome = presentation.grantedOutcome,
                deniedOutcome = presentation.deniedOutcome,
            ),
        )
        _haptics.tryEmit(WhittleHaptic.DecisionShown)
    }

    private fun onCompleted() {
        finishNarration()
        foldSegment()
        _state.update { it.copy(status = null) }
        _haptics.tryEmit(WhittleHaptic.RunDone)
    }

    private fun end(notice: NoticeKind, statusCode: Int? = null) {
        finishNarration()
        _state.update { it.copy(status = null, items = it.items + ThreadItem.Notice(key(), notice, statusCode)) }
    }

    /**
     * A finished segment folds its steps and in-between narration into one summary chip. The answer
     * (the last narration), cards, outcomes and the user's own messages stay in view.
     */
    private fun foldSegment() = _state.update { state ->
        val before = state.items.take(segment.start)
        val segmentItems = state.items.drop(segment.start)
        val answer = segmentItems.lastOrNull { it is ThreadItem.Narration }
        val folded = segmentItems.filter { it is ThreadItem.Steps || (it is ThreadItem.Narration && it !== answer) }
        val stepCount = folded.filterIsInstance<ThreadItem.Steps>().sumOf { it.steps.size }
        if (stepCount == 0) return@update state
        val kept = segmentItems.filterNot { it in folded }
        val seconds = ((graph.clock.nowMillis() - segment.startedAtMillis) / MILLIS_PER_SECOND).coerceAtLeast(1)
        val summary = ThreadItem.Summary(key(), stepCount, seconds, expanded = false, folded = folded)
        state.copy(items = before + summary + kept)
    }

    private fun appendNarration(text: String) = _state.update { state ->
        val last = state.items.lastOrNull()
        if (last is ThreadItem.Narration && last.streaming) {
            state.copy(items = state.items.dropLast(1) + last.copy(text = last.text + text))
        } else {
            state.copy(items = state.items + ThreadItem.Narration(key(), text, streaming = true))
        }
    }

    private fun finishNarration() = _state.update { state ->
        val last = state.items.lastOrNull()
        if (last is ThreadItem.Narration && last.streaming) state.copy(items = state.items.dropLast(1) + last.copy(streaming = false)) else state
    }

    private fun addStep(step: StepModel) = _state.update { state ->
        val last = state.items.lastOrNull()
        if (last is ThreadItem.Steps) {
            state.copy(items = state.items.dropLast(1) + last.copy(steps = last.steps + step))
        } else {
            state.copy(items = state.items + ThreadItem.Steps(key(), listOf(step)))
        }
    }

    private fun updateStep(id: String, change: (StepModel) -> StepModel) = _state.update { state ->
        state.copy(
            items = state.items.map { item ->
                if (item is ThreadItem.Steps && item.steps.any { it.id == id }) item.copy(steps = item.steps.map { if (it.id == id) change(it) else it }) else item
            },
        )
    }

    private fun setStatus(agentState: AgentState, verb: String?) = _state.update { state ->
        state.copy(status = state.status?.copy(state = agentState, verb = verb))
    }

    private fun append(item: ThreadItem) = _state.update { it.copy(items = it.items + item) }

    private fun replace(key: String, change: (ThreadItem) -> ThreadItem) = _state.update { state ->
        state.copy(items = state.items.map { if (it.key == key) change(it) else it })
    }

    private fun arguments(json: String): JsonObject = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (exception: SerializationException) {
        EMPTY_ARGUMENTS
    } catch (exception: IllegalArgumentException) {
        EMPTY_ARGUMENTS
    }

    private fun HarnessError.toNotice(): NoticeKind = when (this) {
        is HarnessError.Authentication -> NoticeKind.FailedKey
        is HarnessError.Provider -> if (statusCode in KEY_REJECTED_CODES) NoticeKind.FailedKey else NoticeKind.FailedProvider
        is HarnessError.Network -> NoticeKind.FailedNetwork
        else -> NoticeKind.FailedGeneric
    }

    private fun key(): String = "item-${nextKey++}"

    private data class Segment(val start: Int, val startedAtMillis: Long)

    private companion object {
        const val HAPTIC_BUFFER = 8
        const val MILLIS_PER_SECOND = 1000L
        val KEY_REJECTED_CODES = setOf(401, 403)
        val EMPTY_ARGUMENTS = JsonObject(emptyMap())
        /** Permission cards always confirm with a check; the words come from config. */
        const val CAPABILITY_APPROVE_ICON = "check"
    }
}
