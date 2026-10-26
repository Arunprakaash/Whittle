package app.whittle.chat

import app.whittle.AppGraph
import app.whittle.agent.DecisionAnswer
import app.whittle.agent.DecisionKind
import app.whittle.agent.PauseReason
import app.whittle.agent.PlanItem
import app.whittle.agent.PlanStatus
import app.whittle.agent.PlanTool
import app.whittle.agent.RunEvent
import app.whittle.agent.RunInput
import app.whittle.agent.RunRequest
import app.whittle.agent.RunStatus
import app.whittle.agent.SystemPromptVariables
import app.whittle.apps.BuildAppTool
import app.whittle.attachments.Attachment
import app.whittle.claudecode.ClaudeCodeLimit
import app.whittle.core.common.HarnessError
import app.whittle.core.common.getOrNull
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import app.whittle.design.components.AgentState
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleMotion
import app.whittle.files.workspacePath
import app.whittle.llm.api.ModelDescriptor
import app.whittle.policy.PolicyDecision
import app.whittle.policy.RunMode
import app.whittle.storage.chats.ChatId
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOrigin
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.presentation.StepText
import app.whittle.tools.workspace.EditFileTool
import app.whittle.tools.workspace.StartProcessTool
import app.whittle.tools.workspace.UseProjectTool
import app.whittle.tools.workspace.WriteFileTool
import app.whittle.tools.workspace.commandIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** One chat's thread as the screen shows it. */
data class SessionState(
    val items: List<ThreadItem> = emptyList(),
    val status: StatusUi? = null,
    /** Goes up whenever a tool finishes in this chat, so an open app reloads after the agent edits it. */
    val workspaceRevision: Int = 0,
    val chatId: ChatId? = null,
)

/**
 * One chat and the task running in it. It turns the harness's run events into the thread the user reads: narration,
 * grouped step rows and inline decision cards. Decisions never hold a run; answering one later continues that run.
 *
 * A session lives on the app's scope, not the screen's: switching to another chat leaves its task running here, and
 * switching back shows it live. The chat is saved as it changes; a run still working when the app closed shows as
 * interrupted, and continuing it is the user's call.
 */
@OptIn(FlowPreview::class)
class ChatSession(
    private val graph: AppGraph,
    parent: CoroutineScope,
    /** Serialises every chat's saves and deletes, so a delete never races a save. */
    private val saveLock: Mutex,
    /** The model in use now, which a continued run is picked up with. */
    private val model: () -> ModelDescriptor?,
    private val listener: Listener,
    /** The first item key: a new chat carries on the count, so cards keyed by item never collide with the last chat's. */
    firstKey: Long = 0,
) : DecisionChat {
    /** What the session tells the app about. */
    interface Listener {
        fun haptic(session: ChatSession, haptic: WhittleHaptic)

        fun event(session: ChatSession, event: ChatEvent)

        /** The chat was saved; the list of chats may have changed. */
        fun saved()

        /** The agent focused on another project. */
        fun projectsChanged()

        /** The task ended and nothing waits to continue. */
        fun idle(session: ChatSession)
    }

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var activeJob: Job? = null
    private var activeRunId: RunId? = null
    private val waitingToContinue = ArrayDeque<RunId>()
    private var history: List<Message> = emptyList()
    private var segment = Segment(start = 0, startedAtMillis = 0)
    private val stepTexts = mutableMapOf<String, StepText>()
    private val parkedCalls = mutableSetOf<String>()

    /** Calls that show elsewhere than as a step, such as use_project in the chip. */
    private val quietCalls = mutableSetOf<String>()
    private val needsAccess = mutableSetOf<String>()
    private val stepArguments = mutableMapOf<String, JsonObject>()
    private var nextKey = firstKey
    private var runIds: List<RunId> = emptyList()

    /** The items as last loaded or saved; unchanged items are never re-saved, so opening a chat doesn't reorder the list. */
    private var savedItems: List<ThreadItem>? = null

    /** Set in a chat an automation started: the chat is titled after it, not after its first message. */
    var automationName: String? = null

    /** The task running now is Claude Code's, not Whittle's own loop. */
    private var claudeCodeRun = false

    override var chatId: ChatId? = null
        private set(value) {
            field = value
            _state.update { it.copy(chatId = value) }
        }

    /** A task is working in this chat now; one waiting on a card isn't. */
    val working: Boolean get() = activeJob?.isActive == true

    /** The next item key, for the session that follows this one. */
    val keyCount: Long get() = nextKey

    init {
        // Save as the thread changes; a short wait keeps streaming from writing every token.
        scope.launch { _state.map { it.items }.distinctUntilChanged().drop(1).debounce(SAVE_DEBOUNCE_MILLIS).collect { save() } }
    }

    /** Gives a new chat its id, which its tasks, files and project are kept under. */
    fun ensureId(): ChatId = chatId ?: ChatId(graph.idGenerator.newId()).also { chatId = it }

    /** Puts a saved chat in this session. Nothing streams or runs after a restart; a run cut off offers Continue. */
    suspend fun load(id: ChatId, document: ChatDocument) {
        chatId = id
        automationName = document.automationName
        history = document.history
        runIds = document.runIds
        nextKey = document.nextKey
        val interrupted = document.activeRunId
            ?.takeIf { graph.checkpoints.load(it).getOrNull()?.status == RunStatus.RUNNING }
            ?.let { ThreadItem.Interrupted(key(), it) }
        val restored = ThreadBackfill(graph.presenter.siteArguments, ::key).apply(document.items)
        val items = restored.map(::settle) + listOfNotNull(interrupted)
        savedItems = items
        _state.update { it.copy(items = items, status = null) }
    }

    /** Starts a task with the user's message, or adds it to the one that's running without stopping it. */
    fun send(
        text: String,
        model: ModelDescriptor,
        forModel: String,
        images: List<ContentBlock.ImageFile>,
        attachments: List<Attachment>,
        pasted: List<ContentBlock.Text>,
    ) {
        val running = activeRunId
        if (activeJob?.isActive == true && running != null) {
            graph.inbox.submit(running, RunInput.Steer(forModel, images, pasted))
            append(ThreadItem.User(key(), text, steer = true, attachments = attachments))
            return
        }
        start(text, model, RunMode.LIVE, preApprovedTools = emptySet(), forModel = forModel, images = images, attachments = attachments, pasted = pasted)
    }

    /**
     * Runs [text], as the user wrote it; the model gets [forModel], which adds the attachment note, with the
     * [images] it can see and any output [pasted] from a card or the screen as untrusted content.
     */
    fun start(
        text: String,
        model: ModelDescriptor,
        mode: RunMode,
        preApprovedTools: Set<ToolId>,
        forModel: String = text,
        images: List<ContentBlock.ImageFile> = emptyList(),
        attachments: List<Attachment> = emptyList(),
        pasted: List<ContentBlock.Text> = emptyList(),
    ) {
        val runId = RunId(graph.idGenerator.newId())
        val chat = ensureId()
        runIds = runIds + runId
        append(ThreadItem.User(key(), text, steer = false, attachments = attachments))
        val request = RunRequest(
            runId = runId,
            model = model,
            userMessage = userMessage(forModel, pasted, images),
            priorMessages = history,
            // The workspace is shared; this id ties the run's processes, outputs and changes to this chat.
            workspace = WorkspaceId(chat.value),
            mode = mode,
            reasoningEffort = graph.modelDefaults.defaultReasoningEffort,
            preApprovedTools = preApprovedTools,
            promptVariables = mapOf(
                SystemPromptVariables.CURRENT_DATE to LocalDate.now().toString(),
                SystemPromptVariables.USER_LOCALE to Locale.getDefault().toLanguageTag(),
                SystemPromptVariables.USER_TIME_ZONE to ZoneId.systemDefault().id,
                SystemPromptVariables.PROJECT to graph.projectDefaults.prompt(graph.workspaces.projectOf(WorkspaceId(chat.value)), graph.linux.workspaceMount),
            ),
        )
        claudeCodeRun = graph.claudeCode.runs(model)
        collect(runId, if (claudeCodeRun) graph.claudeCode.runner.start(request) else graph.runner.start(request))
    }

    override val items: List<ThreadItem> get() = _state.value.items

    /** The user's choice on a card. Applied at the run's next step, or continues a finished run. */
    override fun answer(item: ThreadItem.Decision, approve: Boolean) {
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
        // A card already answered (Decline while setup ran, say) keeps its answer; the run hears one answer per card.
        if (!stillAsking(_state.value.items, item.key)) return
        val presentation = item.presentation
        replace(item.key) { ThreadItem.Resolved(item.key, if (granted) presentation.grantedOutcome else presentation.deniedOutcome, declined = !granted) }
        submit(item.runId, RunInput.CapabilityChanged(item.capability, granted))
    }

    /** Setting up [item]'s capability failed: the card shows [outcome], and the run hears [reason] as a failure. */
    fun setupFailed(item: ThreadItem.Capability, outcome: String, reason: String) {
        if (!stillAsking(_state.value.items, item.key)) return
        replace(item.key) { ThreadItem.Resolved(item.key, outcome, declined = true) }
        submit(item.runId, RunInput.CapabilityChanged(item.capability, granted = false, setupFailure = reason))
    }

    private fun submit(runId: RunId, input: RunInput) {
        graph.inbox.submit(runId, input)
        when {
            activeJob?.isActive != true -> collect(runId, resume(runId))
            activeRunId != runId && runId !in waitingToContinue -> waitingToContinue.addLast(runId)
        }
    }

    /** Picks up a run the app closed in the middle of. Nothing re-runs: an interrupted tool is reported. */
    fun continueRun(item: ThreadItem.Interrupted) {
        _state.update { state -> state.copy(items = state.items.filterNot { it.key == item.key }) }
        collect(item.runId, resume(item.runId))
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
        stopped?.let { scope.launch { carryForward(it); save() } }
    }

    private suspend fun carryForward(runId: RunId) {
        // Claude Code keeps its own conversation; the chat's next task resumes its session instead.
        if (claudeCodeRun) return
        graph.runner.conversationAfter(runId)?.let { history = it }
    }

    /** Picks up [runId] with the runner of the model in use. */
    private fun resume(runId: RunId): Flow<RunEvent> {
        claudeCodeRun = graph.claudeCode.runs(model())
        return if (claudeCodeRun) graph.claudeCode.runner.resume(runId) else graph.runner.resume(runId)
    }

    fun toggleSources(key: String) = replace(key) { (it as ThreadItem.Sources).let { sources -> sources.copy(expanded = !sources.expanded) } }

    fun toggleSummary(key: String) = replace(key) { (it as ThreadItem.Summary).let { summary -> summary.copy(expanded = !summary.expanded) } }

    /** Cuts the running task off the way an app close would, so it can be continued, and saves the chat. */
    fun cutOff() {
        activeJob?.cancel()
        activeJob = null
        landText()
        save()
        activeRunId = null
        waitingToContinue.clear()
    }

    /** Ends the task without saving, for a chat that's being deleted. */
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        activeRunId = null
        waitingToContinue.clear()
    }

    /** The session is done with: what it holds is saved and its work stops. */
    fun close() {
        landText()
        save()
        scope.cancel()
    }

    /** The chat is being deleted: its work stops and nothing more is saved. */
    fun discard() {
        cancel()
        scope.cancel()
    }

    /** Runs the card's command again, in the same folder; the card follows the new process. */
    fun restartProcess(item: ThreadItem.Process) {
        val chat = chatId ?: return
        graph.appScope.launch {
            val directory = graph.workspaces.directory(WorkspaceId(chat.value)).getOrNull() ?: return@launch
            val command = commandIn(item.project, item.command)
            val started = graph.processes.start(WorkspaceId(chat.value), directory, item.name, command, item.port).getOrNull() ?: return@launch
            _state.update { state ->
                state.copy(items = state.items.map { if (it is ThreadItem.Process && it.key == item.key) it.copy(processId = started.id, port = started.port ?: it.port) else it })
            }
        }
    }

    private fun collect(runId: RunId, events: Flow<RunEvent>) {
        activeRunId = runId
        segment = Segment(start = _state.value.items.size, startedAtMillis = graph.clock.nowMillis())
        cited.clear()
        _state.update { it.copy(status = StatusUi(AgentState.Thinking, verb = null, startedAtMillis = segment.startedAtMillis)) }
        activeJob = scope.launch {
            // The loop encrypts and saves a checkpoint every step, so it runs off the main thread.
            events.flowOn(Dispatchers.Default).collect(::onEvent)
            // The next message continues this conversation, whether the run finished, paused or failed.
            carryForward(runId)
            activeJob = null
            activeRunId = null
            save()
            val next = waitingToContinue.removeFirstOrNull()
            if (next != null) collect(next, resume(next)) else listener.idle(this@ChatSession)
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
                streamText(event.text)
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
            is RunEvent.Failed -> if (claudeCodeRun) {
                endClaudeCode(event.error)
            } else {
                end(event.error.toNotice(), statusCode = (event.error as? HarnessError.Provider)?.statusCode)
            }
            is RunEvent.ModelRetrying -> setStatus(AgentState.Thinking, verb = null, phase = StatusPhase.Retrying)
        }
    }

    /** The workspace file a write or edit touches, so its step can open it. */
    private fun writtenFile(tool: String, arguments: JsonObject): String? {
        val argument = when (tool) {
            WriteFileTool.DEFINITION.id.value, EditFileTool.DEFINITION.id.value -> FILE_PATH
            // Claude Code's own file tools, by the argument that names the file.
            else -> graph.claudeCode.config.fileArguments[tool] ?: return null
        }
        val path = (arguments[argument] as? JsonPrimitive)?.contentOrNull ?: return null
        val project = chatId?.let { graph.workspaces.projectOf(WorkspaceId(it.value)) }
        return workspacePath(path, graph.linux.workspaceMount, project)
    }

    private fun onToolCallDecided(event: RunEvent.ToolCallDecided) {
        finishNarration()
        val call = event.toolCall
        val arguments = arguments(call.argumentsJson)
        // Focusing on a project shows in the chip under the title, not as a step.
        if (call.toolName == UseProjectTool.DEFINITION.id.value) {
            quietCalls += call.id.value
            return
        }
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
                addStep(StepModel(call.id.value, text.icon, text.running, StepState.Running, site = text.site, file = writtenFile(call.toolName, arguments)))
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
        // The card is built from what build_app returned, not from the model's possibly shortened copy.
        if (callTools[id] == BuildAppTool.DEFINITION.id.value && !event.result.isError) showApp(event.fullContent)
        if (id in quietCalls) {
            listener.projectsChanged()
            return
        }
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
            listener.haptic(this, WhittleHaptic.StepDone)
            _state.update { it.copy(workspaceRevision = it.workspaceRevision + 1) }
        }
    }

    /** start_process replied: the process gets a card, the one card per name in this chat. */
    /** The card already shows the user's choice; a server started from it still needs its own card. */
    private fun onDecisionResolved(event: RunEvent.DecisionResolved) {
        val output = event.output ?: return
        if (event.decision.toolId == BuildAppTool.DEFINITION.id && !output.isError) showApp(output.content)
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
                state.items + ThreadItem.Process(key(), processId, name, command, port, project = chatId?.let { graph.workspaces.projectOf(WorkspaceId(it.value)) })
            }
            state.copy(items = items)
        }
    }

    /** build_app succeeded: its app gets a card to install from, the one card per package in this chat, moved to the end on a rebuild. */
    private fun showApp(result: String) {
        val card = appCardFromBuild(key(), result) ?: return
        _state.update { it.copy(items = placeAppCard(it.items, card)) }
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
        listener.haptic(this, WhittleHaptic.StepDone)
    }

    private fun onDecisionRequested(event: RunEvent.DecisionRequested) {
        if (event.decision.kind == DecisionKind.QUESTION) return onQuestionRequested(event)
        val call = event.decision.toolCall
        val text = graph.presenter.decision(call.toolName, event.arguments, origin(call.toolName))
        val runId = activeRunId ?: return
        // The run's id too: some providers reuse tool-call ids (call_0) from one turn to the next.
        val key = "decision-${runId.value}-${call.id.value}"
        val fromNotification = graph.approvableFromNotification(event.tool, event.tainted)
        append(ThreadItem.Decision(key = key, runId = runId, toolCallId = call.id, text = text, fromNotification = fromNotification))
        cardShown(ChatEvent.ApprovalNeeded(chatId, key, text, buttons = fromNotification))
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
    private fun cardShown(event: ChatEvent = ChatEvent.NeedsYou(chatId)) {
        listener.haptic(this, WhittleHaptic.DecisionShown)
        listener.event(this, event)
    }

    private fun onCompleted() {
        listener.event(this, ChatEvent.Finished(chatId, failed = false))
        finishNarration()
        val said = _state.value.items.drop(segment.start).filterIsInstance<ThreadItem.Narration>().map { it.text }
        val sources = CitedSources.from(said, cited)
        markAnswer()
        foldSegment()
        if (sources.isNotEmpty()) placeAfterAnswer(ThreadItem.Sources(key(), sources))
        listener.haptic(this, WhittleHaptic.RunDone)
        // A moment of the happy beaver, then the line leaves, unless a new task has already taken it.
        setStatus(AgentState.Done, verb = null)
        scope.launch {
            delay(WhittleMotion.STATUS_DONE_HOLD_MILLIS.toLong())
            _state.update { if (it.status?.state == AgentState.Done) it.copy(status = null) else it }
        }
    }

    /** The run stopped for the user's OK or access: the line leaves, the card stays, and nothing reads as finished. */
    private fun waitForApproval() {
        finishNarration()
        _state.update { it.copy(status = null) }
    }

    private fun end(notice: NoticeKind, statusCode: Int? = null, detail: String? = null, limit: ClaudeCodeLimit? = null) {
        listener.event(this, ChatEvent.Finished(chatId, failed = notice.isFailure))
        finishNarration()
        val window = limit?.window?.let(graph.claudeCode.config.limitNames::get)
        val item = ThreadItem.Notice(key(), notice, statusCode, detail, window, limit?.resetsAtMillis)
        _state.update { it.copy(status = null, items = it.items + item) }
    }

    /** Claude Code's failures in its own words: signed out, the Claude plan used up, or what it reported. */
    private fun endClaudeCode(error: HarnessError) = when {
        error is HarnessError.Authentication -> end(NoticeKind.ClaudeCodeSignedOut)
        error is HarnessError.Provider && error.statusCode == TOO_MANY_REQUESTS ->
            end(NoticeKind.ClaudeCodeLimit, detail = error.message, limit = graph.claudeCode.limit.value)
        else -> end(NoticeKind.ClaudeCodeFailed, detail = error.message)
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

    /** Streamed text not on screen yet, and the wait before it lands. */
    private val pendingText = StringBuilder()
    private var textLanding: Job? = null

    /**
     * Holds streamed text for up to [STREAM_FRAME_MILLIS], then shows it all at once, so a fast stream updates the
     * thread a few times a frame's worth instead of on every token, which would keep the main thread busy.
     */
    private fun streamText(text: String) {
        pendingText.append(text)
        if (textLanding != null) return
        textLanding = scope.launch {
            delay(STREAM_FRAME_MILLIS)
            textLanding = null
            landText()
        }
    }

    /** Shows any held text now: before anything else happens in the thread, and before it's saved. */
    private fun landText() {
        textLanding?.cancel()
        textLanding = null
        if (pendingText.isEmpty()) return
        val text = pendingText.toString()
        pendingText.setLength(0)
        appendNarration(text)
    }

    private fun appendNarration(text: String) = _state.update { state ->
        val last = state.items.lastOrNull()
        if (last is ThreadItem.Narration && last.streaming) {
            state.copy(items = state.items.dropLast(1) + last.copy(text = last.text + text))
        } else {
            state.copy(items = state.items + ThreadItem.Narration(key(), text, streaming = true))
        }
    }

    private fun finishNarration() {
        landText()
        _state.update { state -> narrationFinished(state) }
    }

    private fun narrationFinished(state: SessionState): SessionState {
        val last = state.items.lastOrNull()
        return if (last is ThreadItem.Narration && last.streaming) state.copy(items = state.items.dropLast(1) + last.copy(streaming = false)) else state
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
        val id = ensureId()
        val first = items.filterIsInstance<ThreadItem.User>().firstOrNull()
        val opening = first?.text?.takeIf { it.isNotEmpty() } ?: first?.attachments?.firstOrNull()?.name
        val title = (automationName ?: opening.orEmpty()).take(TITLE_MAX_CHARS)
        val document = ChatDocument(
            schemaVersion = ChatDocument.SCHEMA_VERSION,
            title = title,
            items = items,
            history = history,
            activeRunId = activeRunId,
            nextKey = nextKey,
            runIds = runIds,
            automationName = automationName,
        )
        graph.appScope.launch {
            saveLock.withLock { graph.chats.save(id, title, document.encode()) }
            listener.saved()
        }
    }

    private fun key(): String = "item-${nextKey++}"

    /** update_plan calls in the current run; they never become step rows. */
    private val planCalls = mutableSetOf<String>()

    /** Which tool each call in the current run used, to recognise app results. */
    private val callTools = mutableMapOf<String, String>()

    /** Pages the provider cited in the current run, by cleaned address, with their titles when given. */
    private val cited = linkedMapOf<String, String?>()

    private data class Segment(val start: Int, val startedAtMillis: Long)

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        val KEY_REJECTED_CODES = setOf(401, 403)
        val EMPTY_ARGUMENTS = JsonObject(emptyMap())
        // start_process's reply fields and argument (see ProcessTools).
        const val PROCESS_ID = "id"
        const val PROCESS_NAME = "name"
        const val PROCESS_PORT = "port"
        const val PROCESS_COMMAND = "command"
        /** The path argument of the file tools. */
        const val FILE_PATH = "path"
        const val HOSTED_ACTION_SEPARATOR = "."
        const val SAVE_DEBOUNCE_MILLIS = 400L
        /** How long streamed text waits to land, so a fast stream redraws the thread at most this often. */
        const val STREAM_FRAME_MILLIS = 50L
        const val INSPECTION_MAX_CHARS = 4_000
        const val TITLE_MAX_CHARS = 80
        const val TOO_MANY_REQUESTS = 429
    }
}
