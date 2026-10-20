@file:OptIn(FlowPreview::class)

package app.whittle.chat

import kotlinx.serialization.json.contentOrNull
import app.whittle.tools.workspace.ShowArtifactTool
import app.whittle.artifacts.ArtifactRef
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
    /** Goes up whenever a tool finishes in this chat, so an open artifact reloads after the agent edits it. */
    val workspaceRevision: Int = 0,
) {
    val running: Boolean get() = status != null
}

/** A saved chat in the sidebar. A null [title] shows as an untitled chat. */
data class ChatListItem(val id: ChatId, val title: String?, val updatedAtMillis: Long, val pinned: Boolean)

/** The pinned status line. A null [verb] means the agent is thinking between actions. */
data class StatusUi(val state: AgentState, val verb: String?, val startedAtMillis: Long)

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
        submit(item.runId, RunInput.Answer(item.toolCallId, choice))
    }

    /** Called with the system permission result. A refusal only reaches a run that is still working. */
    fun answer(item: ThreadItem.Capability, granted: Boolean) {
        val presentation = item.presentation
        replace(item.key) { ThreadItem.Resolved(item.key, if (granted) presentation.grantedOutcome else presentation.deniedOutcome, declined = !granted) }
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

    /** Picks up a run the app closed in the middle of. Nothing re-runs: an interrupted tool is reported. */
    fun continueRun(item: ThreadItem.Interrupted) {
        _state.update { state -> state.copy(items = state.items.filterNot { it.key == item.key }) }
        collect(item.runId, graph.runner.resume(item.runId))
    }

    fun stop() {
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

    /** Settings > Delete all chats: every chat, its saved tasks and its workspace files and apps. */
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
        graph.workspaces.delete(WorkspaceId(id.value))
    }

    fun setPinned(id: ChatId, pinned: Boolean) {
        _state.update { state -> state.copy(chats = state.chats.map { if (it.id == id) it.copy(pinned = pinned) else it }) }
        graph.appScope.launch {
            graph.chats.setPinned(id, pinned)
            refreshChats()
        }
    }

    fun changeModel() {
        newChat()
        viewModelScope.launch { graph.connections.clear() }
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
            val items = summaries.map { summary ->
                // Chats saved before titles were stored: read the title from the chat itself.
                val title = summary.title ?: graph.chats.load(summary.id).getOrNull()?.let { decode(it.document)?.title }
                ChatListItem(summary.id, title?.takeIf { it.isNotBlank() }, summary.updatedAtMillis, summary.pinned)
            }
            _state.update { it.copy(chats = items) }
        }
    }

    private fun collect(runId: RunId, events: Flow<RunEvent>) {
        activeRunId = runId
        segment = Segment(start = _state.value.items.size, startedAtMillis = graph.clock.nowMillis())
        citedTitles.clear()
        _state.update { it.copy(status = StatusUi(AgentState.Thinking, verb = null, startedAtMillis = segment.startedAtMillis)) }
        activeJob = viewModelScope.launch {
            // The loop encrypts and saves a checkpoint every step, so it runs off the main thread.
            events.flowOn(Dispatchers.Default).collect { event ->
                onEvent(event)
                // The next message continues this conversation, including any tool results.
                if (event is RunEvent.Completed) graph.checkpoints.load(runId).getOrNull()?.let { history = it.conversation.messages }
            }
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
            is RunEvent.AssistantTextDelta -> appendNarration(event.text)
            is RunEvent.ToolCallDecided -> onToolCallDecided(event)
            is RunEvent.ToolCallFinished -> onToolCallFinished(event)
            is RunEvent.SourceCited -> CitedSources.clean(event.url)?.let { url -> event.title?.let { citedTitles[url.toString()] = it } }
            is RunEvent.HostedToolStarted -> onHostedToolStarted(event)
            is RunEvent.HostedToolFinished -> onHostedToolFinished(event)
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
        val arguments = arguments(call.argumentsJson)
        val text = graph.presenter.step(call.toolName, arguments)
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
        if (id in parkedCalls) return
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
            if (callTools[id] == ShowArtifactTool.DEFINITION.id.value) showArtifact(event.result.content)
        }
    }

    /** The tool checked the page exists; the card points at it in this chat's workspace. */
    private fun showArtifact(result: String) {
        val chat = chatId ?: return
        val fields = arguments(result)
        val path = (fields[ShowArtifactTool.PATH] as? JsonPrimitive)?.contentOrNull ?: return
        val title = (fields[ShowArtifactTool.TITLE] as? JsonPrimitive)?.contentOrNull ?: return
        append(ThreadItem.Artifact(key(), ArtifactRef(workspace = chat.value, path = path, title = title)))
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
        val text = graph.presenter.decision(call.toolName, event.arguments)
        val runId = activeRunId ?: return
        append(
            ThreadItem.Decision(
                key = "decision-${call.id.value}",
                runId = runId,
                toolCallId = call.id,
                text = text,
            ),
        )
        _haptics.tryEmit(WhittleHaptic.DecisionShown)
    }

    private fun onQuestionRequested(event: RunEvent.DecisionRequested) {
        val call = event.decision.toolCall
        val text = graph.presenter.question(call.toolName, event.arguments) ?: return
        val runId = activeRunId ?: return
        append(ThreadItem.Question("question-${call.id.value}", runId, call.id, text))
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
                presentation = presentation,
            ),
        )
        _haptics.tryEmit(WhittleHaptic.DecisionShown)
    }

    private fun onCompleted() {
        finishNarration()
        val said = _state.value.items.drop(segment.start).filterIsInstance<ThreadItem.Narration>().map { it.text }
        val sources = CitedSources.from(said, citedTitles)
        markAnswer()
        foldSegment()
        if (sources.isNotEmpty()) placeAfterAnswer(ThreadItem.Sources(key(), sources))
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

    /** Which tool each call in the current run used, to recognise artifact results. */
    private val callTools = mutableMapOf<String, String>()

    /** Page titles from the provider's citations in the current run, keyed by cleaned address. */
    private val citedTitles = mutableMapOf<String, String>()

    private data class Segment(val start: Int, val startedAtMillis: Long)

    private companion object {
        const val HAPTIC_BUFFER = 8
        const val MILLIS_PER_SECOND = 1000L
        val KEY_REJECTED_CODES = setOf(401, 403)
        val EMPTY_ARGUMENTS = JsonObject(emptyMap())
        const val HOSTED_ACTION_SEPARATOR = "."
        const val SAVE_DEBOUNCE_MILLIS = 400L
        const val INSPECTION_MAX_CHARS = 4_000
        const val TITLE_MAX_CHARS = 80
    }
}
