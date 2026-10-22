@file:OptIn(FlowPreview::class)

package app.whittle.chat

import app.whittle.storage.secrets.SecretKey
import kotlinx.coroutines.delay
import app.whittle.design.tokens.WhittleMotion
import app.whittle.agent.PlanTool
import app.whittle.agent.PlanStatus
import app.whittle.agent.PlanItem
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOrigin
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.whittle.AppGraph
import app.whittle.agent.DecisionAnswer
import app.whittle.agent.DecisionKind
import app.whittle.agent.PauseReason
import app.whittle.agent.RunEvent
import app.whittle.agent.RunInput
import app.whittle.agent.RunRequest
import app.whittle.agent.RunStatus
import app.whittle.agent.SystemPromptVariables
import app.whittle.core.common.HarnessError
import app.whittle.core.common.getOrNull
import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.design.components.AgentState
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.llm.api.ModelDescriptor
import app.whittle.policy.PolicyDecision
import app.whittle.policy.RunMode
import app.whittle.storage.chats.ChatId
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.ProcessInfo
import app.whittle.tools.workspace.StartProcessTool
import app.whittle.tools.presentation.StepText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

data class ChatState(
    val model: ModelDescriptor? = null,
    val items: List<ThreadItem> = emptyList(),
    val input: String = "",
    val status: StatusUi? = null,
    /** True until the last chat has been loaded, so the empty state doesn't flash at launch. */
    val restoring: Boolean = true,
    /** Saved chats, newest first, for the sidebar. */
    val chats: List<ChatListItem> = emptyList(),
    val currentChat: ChatId? = null,
    /** Apps the agent made, across every chat, newest chat first. */
    /** Goes up whenever a tool finishes in this chat, so an open app reloads after the agent edits it. */
    val workspaceRevision: Int = 0,
) {
    val running: Boolean get() = status != null
}

/** What happened in a chat that the user may want to hear about while away from the screen. */
sealed interface ChatEvent {
    /** A question, approval or access card appeared. */
    data object NeedsYou : ChatEvent

    /** The task ended: done, or stopped by an error or a limit ([failed]). */
    data class Finished(val failed: Boolean) : ChatEvent
}

/** A saved chat in the sidebar. A null [title] shows as an untitled chat. */
data class ChatListItem(val id: ChatId, val title: String?, val updatedAtMillis: Long, val pinned: Boolean)

/** The pinned status line. A null [verb] means the agent is thinking between actions. */
data class StatusUi(
    val state: AgentState,
    val verb: String?,
    val startedAtMillis: Long,
    /** Steps this run has started so far; a run that only writes text has none. */
    val steps: Int = 0,
    /** The agent's plan for this run, when it wrote one. */
    val plan: List<PlanItem>? = null,
    /** The reply is streaming in, with no step running. */
    val writing: Boolean = false,
    /** Housekeeping the harness is doing between steps, shown in place of "Thinking". */
    val phase: StatusPhase? = null,
)

enum class StatusPhase {
    /** The provider failed for a passing reason and the call is being tried again. */
    Retrying,
}

/**
 * Turns the harness's run events into the thread the user reads: narration, grouped step rows and
 * inline decision cards. Decisions never hold a run; answering one later continues that run.
 *
 * The chat is saved on this phone as it changes and restored at launch. A run that was still working
 * when the app closed shows as interrupted; continuing it is the user's call.
 */
class ChatViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _haptics = MutableSharedFlow<WhittleHaptic>(extraBufferCapacity = HAPTIC_BUFFER)
    val haptics: SharedFlow<WhittleHaptic> = _haptics.asSharedFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Moments worth telling the user about when they aren't looking, e.g. as a notification. */
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private var activeJob: Job? = null
    private var activeRunId: RunId? = null
    private val waitingToContinue = ArrayDeque<RunId>()
    private var history: List<Message> = emptyList()
    private var segment = Segment(start = 0, startedAtMillis = 0)
    private val stepTexts = mutableMapOf<String, StepText>()
    private val parkedCalls = mutableSetOf<String>()
    private val needsAccess = mutableSetOf<String>()
    private val stepArguments = mutableMapOf<String, JsonObject>()
    private var nextKey = 0L
    private var chatId: ChatId? = null
        set(value) {
            field = value
            _state.update { it.copy(currentChat = value) }
        }
    private val saveLock = Mutex()
    private var runIds: List<RunId> = emptyList()

    /** The items as last loaded or saved; unchanged items are never re-saved, so opening a chat doesn't reorder the list. */
    private var savedItems: List<ThreadItem>? = null

    init {
        viewModelScope.launch { graph.connections.model.collect { model -> _state.update { it.copy(model = model) } } }
        restore()
    }

    override fun onCleared() {
        // The screen is going away, possibly mid-run: keep the run id so the next launch can offer to continue.
        save()
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
        val chat = chatId ?: ChatId(graph.idGenerator.newId()).also { chatId = it }
        runIds = runIds + runId
        append(ThreadItem.User(key(), text, steer = false))
        val request = RunRequest(
            runId = runId,
            model = model,
            userMessage = Message.userText(text),
            priorMessages = history,
            // One workspace per chat: files from earlier turns are still there for follow-ups.
            workspace = WorkspaceId(chat.value),
            mode = RunMode.LIVE,
            reasoningEffort = graph.modelDefaults.defaultReasoningEffort,
            preApprovedTools = emptySet(),
            promptVariables = mapOf(
                SystemPromptVariables.CURRENT_DATE to LocalDate.now().toString(),
                SystemPromptVariables.USER_LOCALE to Locale.getDefault().toLanguageTag(),
                SystemPromptVariables.USER_TIME_ZONE to ZoneId.systemDefault().id,
            ),
        )
        collect(runId, graph.runner.start(request))
    }

    /** The user's choice on a card. Applied at the run's next step, or continues a finished run. */
    fun answer(item: ThreadItem.Decision, approve: Boolean) {
        replace(item.key) {
            ThreadItem.Resolved(item.key, if (approve) item.text.approvedOutcome else item.text.declinedOutcome, declined = !approve)
        }
        val answer = if (approve) DecisionAnswer.APPROVED else DecisionAnswer.DECLINED
        submit(item.runId, RunInput.Decision(item.toolCallId, answer))
    }

    /** The card stays in the thread with the choice marked; only the first tap counts. */
    fun answer(item: ThreadItem.Question, choice: String) {
        if (item.answer != null) return
        replace(item.key) { (it as ThreadItem.Question).copy(answer = choice) }
        // The pick is the user's reply: it reads as their message, and what the agent does next follows it.
        append(ThreadItem.User(key(), choice, steer = activeJob?.isActive == true))
        submit(item.runId, RunInput.Answer(item.toolCallId, choice))
    }

    /** Called with the system permission result; either way the paused run continues and hears it. */
    fun answer(item: ThreadItem.Capability, granted: Boolean) {
        val presentation = item.presentation
        replace(item.key) { ThreadItem.Resolved(item.key, if (granted) presentation.grantedOutcome else presentation.deniedOutcome, declined = !granted) }
        submit(item.runId, RunInput.CapabilityChanged(item.capability, granted))
    }

    private fun submit(runId: RunId, input: RunInput) {
        graph.inbox.submit(runId, input)
        when {
            activeJob?.isActive != true -> collect(runId, graph.runner.resume(runId))
            activeRunId != runId && runId !in waitingToContinue -> waitingToContinue.addLast(runId)
        }
    }

    /** Picks up a run the app closed in the middle of. Nothing re-runs: an interrupted tool is reported. */
    fun continueRun(item: ThreadItem.Interrupted) {
        _state.update { state -> state.copy(items = state.items.filterNot { it.key == item.key }) }
        collect(item.runId, graph.runner.resume(item.runId))
    }

    fun stop() {
        // Nothing is running while the line shows a finished task.
        if (activeJob?.isActive != true) {
            _state.update { it.copy(status = null) }
            return
        }
        val stopped = activeRunId
        activeJob?.cancel()
        activeJob = null
        activeRunId = null
        finishNarration()
        _state.update { state ->
            state.copy(
                status = null,
                items = state.items.map { item ->
                    if (item is ThreadItem.Steps) item.copy(steps = item.steps.map { if (it.status == StepState.Running) it.copy(status = StepState.Stopped) else it }) else item
                } + ThreadItem.Notice(key(), NoticeKind.Stopped),
            )
        }
        // A follow-up after Stop still knows what was asked and what had been done.
        stopped?.let { viewModelScope.launch { carryForward(it); save() } }
    }

    private suspend fun carryForward(runId: RunId) {
        graph.runner.conversationAfter(runId)?.let { history = it }
    }

    fun toggleSources(key: String) = replace(key) { (it as ThreadItem.Sources).let { sources -> sources.copy(expanded = !sources.expanded) } }

    fun toggleSummary(key: String) = replace(key) { (it as ThreadItem.Summary).let { summary -> summary.copy(expanded = !summary.expanded) } }

    /** Starts a fresh chat; the current one stays saved on the phone. */
    fun newChat() {
        leaveCurrentChat()
        viewModelScope.launch { show(id = null, document = null) }
    }

    /** Opens a saved chat. A task still running in the current chat pauses and offers Continue there later. */
    fun openChat(id: ChatId) {
        if (id == chatId) return
        leaveCurrentChat()
        viewModelScope.launch { show(id, graph.chats.load(id).getOrNull()?.let { decode(it.document) }) }
    }

    /** Removes a chat for good: its messages, its saved runs and its workspace files. */
    fun deleteChat(id: ChatId) {
        if (id == chatId) {
            stopWithoutSaving()
            viewModelScope.launch { show(id = null, document = null) }
        }
        _state.update { state -> state.copy(chats = state.chats.filterNot { it.id == id }) }
        graph.appScope.launch {
            saveLock.withLock { purge(id) }
            refreshChats()
        }
    }

    /** Settings > Delete all chats: every chat, its saved tasks and its workspace files. */
    fun deleteAllChats() {
        stopWithoutSaving()
        viewModelScope.launch { show(id = null, document = null) }
        _state.update { it.copy(chats = emptyList()) }
        graph.appScope.launch {
            saveLock.withLock { graph.chats.list().getOrNull().orEmpty().forEach { purge(it.id) } }
            refreshChats()
        }
    }

    private suspend fun purge(id: ChatId) {
        val document = graph.chats.load(id).getOrNull()?.let { decode(it.document) }
        graph.chats.delete(id)
        document?.let { graph.checkpoints.delete(it.runIds + listOfNotNull(it.activeRunId)) }
        graph.processes.stopAll(WorkspaceId(id.value))
        graph.processes.clear(WorkspaceId(id.value))
        graph.workspaces.delete(WorkspaceId(id.value))
    }

    fun setPinned(id: ChatId, pinned: Boolean) {
        _state.update { state -> state.copy(chats = state.chats.map { if (it.id == id) it.copy(pinned = pinned) else it }) }
        graph.appScope.launch {
            graph.chats.setPinned(id, pinned)
            refreshChats()
        }
    }

    /** Leaves the current provider: its key is forgotten on this phone and the user connects another. */
    fun switchProvider() {
        val provider = _state.value.model?.providerId
        newChat()
        viewModelScope.launch {
            provider?.let { graph.secrets.delete(SecretKey.providerApiKey(it)) }
            graph.connections.clear()
        }
    }

    /** Saves the current chat; a running task is cut off the way an app close would, so it can be continued. */
    private fun leaveCurrentChat() {
        activeJob?.cancel()
        activeJob = null
        save()
        activeRunId = null
        waitingToContinue.clear()
    }

    private fun stopWithoutSaving() {
        activeJob?.cancel()
        activeJob = null
        activeRunId = null
        waitingToContinue.clear()
    }

    /** Puts a chat on screen: [document] null means a fresh, empty chat. */
    private suspend fun show(id: ChatId?, document: ChatDocument?) {
        chatId = id.takeIf { document != null }
        history = document?.history.orEmpty()
        runIds = document?.runIds.orEmpty()
        nextKey = document?.nextKey ?: nextKey
        val interrupted = document?.activeRunId
            ?.takeIf { graph.checkpoints.load(it).getOrNull()?.status == RunStatus.RUNNING }
            ?.let { ThreadItem.Interrupted(key(), it) }
        val restored = ThreadBackfill(graph.presenter.siteArguments, ::key).apply(document?.items.orEmpty())
        val items = restored.map(::settle) + listOfNotNull(interrupted)
        savedItems = items
        _state.update { it.copy(items = items, status = null, input = "") }
    }

    private fun refreshChats() {
        graph.appScope.launch {
            val summaries = graph.chats.list().getOrNull() ?: return@launch
            // Chats saved before titles were stored get theirs from the document.
            val loaded = summaries.map { summary ->
                val document = graph.chats.load(summary.id).getOrNull()?.let { decode(it.document) }
                val title = summary.title ?: document?.title
                ChatListItem(summary.id, title?.takeIf { it.isNotBlank() }, summary.updatedAtMillis, summary.pinned) to document
            }
            _state.update { it.copy(chats = loaded.map { (chat, _) -> chat }) }
        }
    }

    private fun collect(runId: RunId, events: Flow<RunEvent>) {
        activeRunId = runId
        segment = Segment(start = _state.value.items.size, startedAtMillis = graph.clock.nowMillis())
        cited.clear()
        _state.update { it.copy(status = StatusUi(AgentState.Thinking, verb = null, startedAtMillis = segment.startedAtMillis)) }
        activeJob = viewModelScope.launch {
            // The loop encrypts and saves a checkpoint every step, so it runs off the main thread.
            events.flowOn(Dispatchers.Default).collect(::onEvent)
            // The next message continues this conversation, whether the run finished, paused or failed.
            carryForward(runId)
            activeJob = null
            activeRunId = null
            save()
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
            is RunEvent.AssistantTextDelta -> {
                // While the reply streams in and no step is running, the agent is writing, not thinking or searching.
                val status = _state.value.status
                if (status != null && !status.writing && status.state != AgentState.Done && !stepRunning()) {
                    setStatus(AgentState.Acting, verb = null, writing = true)
                }
                appendNarration(event.text)
            }
            is RunEvent.ToolCallDecided -> onToolCallDecided(event)
            is RunEvent.ToolCallFinished -> onToolCallFinished(event)
            is RunEvent.SourceCited -> CitedSources.clean(event.url)?.let { url -> cited[url.toString()] = event.title ?: cited[url.toString()] }
            is RunEvent.HostedToolStarted -> onHostedToolStarted(event)
            is RunEvent.HostedToolFinished -> onHostedToolFinished(event)
            is RunEvent.DecisionRequested -> onDecisionRequested(event)
            is RunEvent.DecisionResolved -> onDecisionResolved(event)
            is RunEvent.QuestionAnswered -> Unit // The card already shows the user's choice.
            is RunEvent.CapabilityRequested -> onCapabilityRequested(event)
            is RunEvent.Completed -> onCompleted()
            is RunEvent.Paused -> when (event.reason) {
                // The card is already in the thread (and its "needs you" notification sent); answering it continues.
                PauseReason.AWAITING_USER -> waitForApproval()
                PauseReason.TOKEN_BUDGET -> end(NoticeKind.PausedBudget)
                PauseReason.STEP_LIMIT -> end(NoticeKind.PausedSteps)
            }
            is RunEvent.Failed -> end(event.error.toNotice(), statusCode = (event.error as? HarnessError.Provider)?.statusCode)
            is RunEvent.ModelRetrying -> setStatus(AgentState.Thinking, verb = null, phase = StatusPhase.Retrying)
        }
    }

    private fun onToolCallDecided(event: RunEvent.ToolCallDecided) {
        finishNarration()
        val call = event.toolCall
        val arguments = arguments(call.argumentsJson)
        // A plan isn't a step: it updates the checklist in place.
        if (call.toolName == PlanTool.ID) {
            planCalls += call.id.value
            if (event.decision is PolicyDecision.Allow) PlanTool.parse(arguments)?.let(::showPlan)
            return
        }
        val text = graph.presenter.step(call.toolName, arguments, origin(call.toolName))
        stepTexts[call.id.value] = text
        callTools[call.id.value] = call.toolName
        stepArguments[call.id.value] = arguments
        when (event.decision) {
            is PolicyDecision.Allow -> {
                addStep(StepModel(call.id.value, text.icon, text.running, StepState.Running, site = text.site))
                setStatus(AgentState.Acting, text.running)
            }
            is PolicyDecision.Deny -> addStep(StepModel(call.id.value, text.icon, text.running, StepState.Blocked, site = text.site))
            is PolicyDecision.Ask -> parkedCalls += call.id.value
        }
    }

    private fun onToolCallFinished(event: RunEvent.ToolCallFinished) {
        val id = event.result.toolCallId.value
        // A server that ended at once still gets its card: its output and Ask to fix are on it.
        if (callTools[id] == StartProcessTool.DEFINITION.id.value) showProcess(event.result.content, stepArguments[id] ?: EMPTY_ARGUMENTS)
        if (id in parkedCalls || id in planCalls) return
        val text = stepTexts[id]
        updateStep(id) { step ->
            when {
                step.status == StepState.Blocked -> step
                id in needsAccess -> step.copy(status = StepState.NeedsAccess)
                else -> step.copy(
                    label = text?.done ?: step.label,
                    status = if (event.result.isError) StepState.Failed else StepState.Done,
                    inspection = inspection(stepArguments[id] ?: EMPTY_ARGUMENTS, event.result.content),
                )
            }
        }
        if (!event.result.isError) {
            _haptics.tryEmit(WhittleHaptic.StepDone)
            _state.update { it.copy(workspaceRevision = it.workspaceRevision + 1) }
        }
    }

    /** start_process replied: the process gets a card, the one card per name in this chat. */
    /** The card already shows the user's choice; a server started from it still needs its own card. */
    private fun onDecisionResolved(event: RunEvent.DecisionResolved) {
        val output = event.output ?: return
        if (event.decision.toolId != StartProcessTool.DEFINITION.id) return
        showProcess(output.content, arguments(event.decision.toolCall.argumentsJson))
    }

    private fun showProcess(result: String, arguments: JsonObject) {
        val fields = arguments(result)
        val processId = (fields[PROCESS_ID] as? JsonPrimitive)?.contentOrNull ?: return
        val name = (fields[PROCESS_NAME] as? JsonPrimitive)?.contentOrNull ?: return
        val command = (arguments[PROCESS_COMMAND] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val port = (fields[PROCESS_PORT] as? JsonPrimitive)?.intOrNull
        _state.update { state ->
            val shown = state.items.any { it is ThreadItem.Process && it.name == name }
            val items = if (shown) {
                state.items.map { if (it is ThreadItem.Process && it.name == name) it.copy(processId = processId, command = command, port = port) else it }
            } else {
                state.items + ThreadItem.Process(key(), processId, name, command, port)
            }
            state.copy(items = items)
        }
    }

    /** Every process this run of the app knows; a card whose process isn't here isn't running. */
    val processes: StateFlow<List<ProcessInfo>> get() = graph.processes.processes

    fun processOutput(item: ThreadItem.Process, lines: Int): String = graph.processes.output(item.processId, lines).orEmpty()

    fun stopProcess(item: ThreadItem.Process) {
        graph.appScope.launch { graph.processes.stop(item.processId) }
    }

    /** Runs the card's command again, in the same folder; the card follows the new process. */
    fun restartProcess(item: ThreadItem.Process) {
        val chat = chatId ?: return
        graph.appScope.launch {
            val directory = graph.workspaces.directory(WorkspaceId(chat.value)).getOrNull() ?: return@launch
            val started = graph.processes.start(WorkspaceId(chat.value), directory, item.name, item.command, item.port).getOrNull() ?: return@launch
            _state.update { state ->
                state.copy(items = state.items.map { if (it is ThreadItem.Process && it.key == item.key) it.copy(processId = started.id, port = started.port ?: it.port) else it })
            }
        }
    }

    /** Sends [text] as the user's message, as if typed: Ask to fix on a card that stopped. */
    fun sendText(text: String) {
        _state.update { it.copy(input = text) }
        send()
    }

    /** The current run's plan replaces the previous one in place; the status line follows the active step. */
    private fun showPlan(steps: List<PlanItem>) = _state.update { state ->
        val existing = state.items.withIndex().lastOrNull { (index, item) -> index >= segment.start && item is ThreadItem.Plan }
        val items = if (existing != null) {
            state.items.mapIndexed { index, item -> if (index == existing.index) (item as ThreadItem.Plan).copy(steps = steps) else item }
        } else {
            state.items + ThreadItem.Plan(key(), steps)
        }
        val active = steps.firstOrNull { it.status == PlanStatus.IN_PROGRESS }?.content
        state.copy(items = items, status = state.status?.copy(plan = steps, verb = active ?: state.status.verb))
    }

    /** Provider-run tools, like web search, show as steps like any other. Policy allowed them before the model call. */
    private fun onHostedToolStarted(event: RunEvent.HostedToolStarted) {
        val text = graph.presenter.step(event.tool.toolName, EMPTY_ARGUMENTS)
        addStep(StepModel(event.callId, text.icon, text.running, StepState.Running))
        setStatus(AgentState.Acting, text.running)
    }

    private fun onHostedToolFinished(event: RunEvent.HostedToolFinished) {
        // Each action (search, open a page, ...) has its own wording, keyed "<tool>.<action>".
        val text = graph.presenter.step("${event.tool.toolName}$HOSTED_ACTION_SEPARATOR${event.action}", event.arguments)
        val done = StepModel(event.callId, text.icon, text.done, StepState.Done, inspection(event.arguments, result = ""), site = text.site)
        val shown = _state.value.items.any { item -> item is ThreadItem.Steps && item.steps.any { it.id == event.callId } }
        if (shown) updateStep(event.callId) { done } else addStep(done)
        _haptics.tryEmit(WhittleHaptic.StepDone)
    }

    private fun onDecisionRequested(event: RunEvent.DecisionRequested) {
        if (event.decision.kind == DecisionKind.QUESTION) return onQuestionRequested(event)
        val call = event.decision.toolCall
        val text = graph.presenter.decision(call.toolName, event.arguments, origin(call.toolName))
        val runId = activeRunId ?: return
        append(
            ThreadItem.Decision(
                key = "decision-${call.id.value}",
                runId = runId,
                toolCallId = call.id,
                text = text,
            ),
        )
        cardShown()
    }

    private fun onQuestionRequested(event: RunEvent.DecisionRequested) {
        val call = event.decision.toolCall
        val text = graph.presenter.question(call.toolName, event.arguments) ?: return
        val runId = activeRunId ?: return
        append(ThreadItem.Question("question-${call.id.value}", runId, call.id, text))
        cardShown()
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
                presentation = presentation,
            ),
        )
        cardShown()
    }

    /** A card is waiting for the user's choice; the run carries on regardless. */
    private fun cardShown() {
        _haptics.tryEmit(WhittleHaptic.DecisionShown)
        _events.tryEmit(ChatEvent.NeedsYou)
    }

    private fun onCompleted() {
        _events.tryEmit(ChatEvent.Finished(failed = false))
        finishNarration()
        val said = _state.value.items.drop(segment.start).filterIsInstance<ThreadItem.Narration>().map { it.text }
        val sources = CitedSources.from(said, cited)
        markAnswer()
        foldSegment()
        if (sources.isNotEmpty()) placeAfterAnswer(ThreadItem.Sources(key(), sources))
        _haptics.tryEmit(WhittleHaptic.RunDone)
        // A moment of the happy beaver, then the line leaves, unless a new task has already taken it.
        setStatus(AgentState.Done, verb = null)
        viewModelScope.launch {
            delay(WhittleMotion.STATUS_DONE_HOLD_MILLIS.toLong())
            _state.update { if (it.status?.state == AgentState.Done) it.copy(status = null) else it }
        }
    }

    /** The run stopped for the user's OK or access: the line leaves, the card stays, and nothing reads as finished. */
    private fun waitForApproval() {
        finishNarration()
        _state.update { it.copy(status = null) }
    }

    private fun end(notice: NoticeKind, statusCode: Int? = null) {
        _events.tryEmit(ChatEvent.Finished(failed = notice.isFailure))
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
        val folded = segmentItems.filter { it is ThreadItem.Steps || it is ThreadItem.Plan || (it is ThreadItem.Narration && it !== answer) }
        val stepCount = folded.filterIsInstance<ThreadItem.Steps>().sumOf { it.steps.size }
        if (stepCount == 0) return@update state
        val kept = segmentItems.filterNot { it in folded }
        val seconds = ((graph.clock.nowMillis() - segment.startedAtMillis) / MILLIS_PER_SECOND).coerceAtLeast(1)
        val summary = ThreadItem.Summary(key(), stepCount, seconds, expanded = false, folded = folded)
        state.copy(items = before + summary + kept)
    }

    /** The segment's last narration is its answer. */
    private fun markAnswer() = _state.update { state ->
        val answer = state.items.indexOfLast { it is ThreadItem.Narration }
        if (answer < segment.start) return@update state
        state.copy(items = state.items.mapIndexed { index, item -> if (index == answer) (item as ThreadItem.Narration).copy(answer = true) else item })
    }

    /** Sources sit right under the answer, above any cards the run left open. */
    private fun placeAfterAnswer(item: ThreadItem) = _state.update { state ->
        val answer = state.items.indexOfLast { it is ThreadItem.Narration }
        if (answer < 0) return@update state.copy(items = state.items + item)
        state.copy(items = state.items.take(answer + 1) + item + state.items.drop(answer + 1))
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
        val counted = state.copy(status = state.status?.let { it.copy(steps = it.steps + 1) })
        val last = counted.items.lastOrNull()
        if (last is ThreadItem.Steps) {
            counted.copy(items = counted.items.dropLast(1) + last.copy(steps = last.steps + step))
        } else {
            counted.copy(items = counted.items + ThreadItem.Steps(key(), listOf(step)))
        }
    }

    private fun updateStep(id: String, change: (StepModel) -> StepModel) = _state.update { state ->
        state.copy(
            items = state.items.map { item ->
                if (item is ThreadItem.Steps && item.steps.any { it.id == id }) item.copy(steps = item.steps.map { if (it.id == id) change(it) else it }) else item
            },
        )
    }

    /** With no step running, the line shows the plan's current step, when there is a plan. */
    private fun setStatus(agentState: AgentState, verb: String?, writing: Boolean = false, phase: StatusPhase? = null) = _state.update { state ->
        val planStep = state.status?.plan?.firstOrNull { it.status == PlanStatus.IN_PROGRESS }?.content
        val shown = if (phase != null) null else verb ?: planStep
        state.copy(status = state.status?.copy(state = agentState, verb = shown, writing = writing, phase = phase))
    }

    private fun stepRunning(): Boolean = _state.value.items.drop(segment.start).any { item ->
        item is ThreadItem.Steps && item.steps.any { it.status == StepState.Running }
    }

    private fun append(item: ThreadItem) = _state.update { it.copy(items = it.items + item) }

    /** How a connected server presents [toolName]: its own title, the service and its logo. */
    private fun origin(toolName: String): ToolOrigin? = graph.tools.find(ToolId(toolName))?.definition?.origin

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

    /**
     * The call's arguments, then its result: JSON fields one per line, multi-line text (a command's
     * output, a file) as a block. Capped so a huge result can't swamp the thread.
     */
    private fun inspection(arguments: JsonObject, result: String): String = buildString {
        arguments.forEach { (name, value) -> appendField(name, value) }
        val fields = try {
            Json.parseToJsonElement(result) as? JsonObject
        } catch (exception: SerializationException) {
            null
        }
        if (isNotEmpty()) appendLine()
        if (fields != null) fields.forEach { (name, value) -> appendField(name, value) } else append(result)
    }.trimEnd().take(INSPECTION_MAX_CHARS)

    private fun StringBuilder.appendField(name: String, value: JsonElement) {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: value.toString()
        if ('\n' in text.trimEnd()) {
            appendLine("$name:")
            appendLine(text.trimEnd())
        } else {
            appendLine("$name: $text")
        }
    }

    private fun HarnessError.toNotice(): NoticeKind = when (this) {
        is HarnessError.Authentication -> NoticeKind.FailedKey
        is HarnessError.Provider -> if (statusCode in KEY_REJECTED_CODES) NoticeKind.FailedKey else NoticeKind.FailedProvider
        is HarnessError.Network -> NoticeKind.FailedNetwork
        else -> NoticeKind.FailedGeneric
    }

    private fun restore() = viewModelScope.launch {
        val stored = graph.chats.latest().getOrNull()
        val document = stored?.let { decode(it.document) }
        if (stored != null && document != null) {
            show(stored.id, document)
        }
        refreshChats()
        _state.update { it.copy(restoring = false) }
        // From here on, save as the thread changes; a short wait keeps streaming from writing every token.
        _state.map { it.items }.distinctUntilChanged().drop(1).debounce(SAVE_DEBOUNCE_MILLIS).collect { save() }
    }

    /** Nothing streams or runs after a restart; whatever was in flight is shown as stopped. */
    private fun settle(item: ThreadItem): ThreadItem = when (item) {
        is ThreadItem.Narration -> item.copy(streaming = false)
        is ThreadItem.Steps -> item.copy(steps = item.steps.map { if (it.status == StepState.Running) it.copy(status = StepState.Stopped) else it })
        is ThreadItem.Summary -> item.copy(folded = item.folded.map(::settle))
        else -> item
    }

    /** Writes the chat on the app's own scope, so closing the screen never cuts a save short. */
    private fun save() {
        val items = _state.value.items
        if (items.isEmpty() || items == savedItems) return
        savedItems = items
        val id = chatId ?: ChatId(graph.idGenerator.newId()).also { chatId = it }
        val title = items.filterIsInstance<ThreadItem.User>().firstOrNull()?.text.orEmpty().take(TITLE_MAX_CHARS)
        val document = ChatDocument(
            schemaVersion = ChatDocument.SCHEMA_VERSION,
            title = title,
            items = items,
            history = history,
            activeRunId = activeRunId,
            nextKey = nextKey,
            runIds = runIds,
        )
        graph.appScope.launch {
            saveLock.withLock { graph.chats.save(id, title, document.encode()) }
            refreshChats()
        }
    }

    private fun decode(document: String): ChatDocument? = ChatDocument.decode(document)

    private fun key(): String = "item-${nextKey++}"

    /** update_plan calls in the current run; they never become step rows. */
    private val planCalls = mutableSetOf<String>()

    /** Which tool each call in the current run used, to recognise app results. */
    private val callTools = mutableMapOf<String, String>()

    /** Pages the provider cited in the current run, by cleaned address, with their titles when given. */
    private val cited = linkedMapOf<String, String?>()

    private data class Segment(val start: Int, val startedAtMillis: Long)

    private companion object {
        const val HAPTIC_BUFFER = 8
        const val EVENT_BUFFER = 8
        const val MILLIS_PER_SECOND = 1000L
        val KEY_REJECTED_CODES = setOf(401, 403)
        val EMPTY_ARGUMENTS = JsonObject(emptyMap())
        // start_process's reply fields and argument (see ProcessTools).
        const val PROCESS_ID = "id"
        const val PROCESS_NAME = "name"
        const val PROCESS_PORT = "port"
        const val PROCESS_COMMAND = "command"
        const val HOSTED_ACTION_SEPARATOR = "."
        const val SAVE_DEBOUNCE_MILLIS = 400L
        const val INSPECTION_MAX_CHARS = 4_000
        const val TITLE_MAX_CHARS = 80
    }
}
