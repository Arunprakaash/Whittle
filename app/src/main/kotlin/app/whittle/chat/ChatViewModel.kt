package app.whittle.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.whittle.AppGraph
import app.whittle.agent.PastedKind
import app.whittle.agent.PlanItem
import app.whittle.agent.pastedOutput
import app.whittle.apps.InstallResult
import app.whittle.apps.PowerfulDeclaration
import app.whittle.apps.SetupOutcome
import app.whittle.apps.ToolchainFailure
import app.whittle.apps.prepareAppBuilding
import app.whittle.apps.setupReasonForModel
import app.whittle.attachments.Attachment
import app.whittle.attachments.AttachmentNames
import app.whittle.attachments.AttachmentProblem
import app.whittle.attachments.Imported
import app.whittle.attachments.SharedItems
import app.whittle.attachments.WorkspaceImages
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrNull
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.Role
import app.whittle.design.components.AgentState
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.files.ProjectFolder
import app.whittle.linux.LinuxState
import app.whittle.llm.api.ModelDescriptor
import app.whittle.policy.RunMode
import app.whittle.settings.AttachmentDefaults
import app.whittle.settings.FilesDefaults
import app.whittle.storage.chats.ChatId
import app.whittle.storage.secrets.SecretKey
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.ChatProjects
import app.whittle.tools.workspace.ProcessInfo
import app.whittle.tools.presentation.DecisionText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

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
    /** Goes up whenever a tool finishes in this chat, so an open app reloads after the agent edits it. */
    val workspaceRevision: Int = 0,
    /** Other chats with a task working now, which keep going while this one is open. */
    val workingElsewhere: Set<ChatId> = emptySet(),
    /** The project this chat works on; null for the whole workspace. */
    val project: String? = null,
    /** The workspace's projects, most recently changed first, to pick from. */
    val projects: List<ProjectFolder> = emptyList(),
    /** Photos and files added to the next message, already saved in the workspace. */
    val attachments: List<Attachment> = emptyList(),
    /** How many picked items are still being saved. */
    val attaching: Int = 0,
) {
    val running: Boolean get() = status != null

    /** An attached photo the current model would only see as a file. */
    val imagesUnseen: Boolean get() = model?.capabilities?.vision == false && attachments.any { it.isImage }
}

/** What happened in a chat that the user may want to hear about while away from the screen. */
sealed interface ChatEvent {
    /** The chat it happened in; null only before a chat is saved. */
    val chat: ChatId?

    /** A question or access card appeared. */
    data class NeedsYou(override val chat: ChatId?) : ChatEvent

    /** The approval card [key] appeared, asking in [text]'s words about a call at [tier]. */
    data class ApprovalNeeded(override val chat: ChatId?, val key: String, val text: DecisionText, val tier: RiskTier) : ChatEvent

    /** The task ended: done, or stopped by an error or a limit ([failed]). */
    data class Finished(override val chat: ChatId?, val failed: Boolean) : ChatEvent
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

/** A chat with a task on its status line: the open one, or one working while the user is in another. */
data class ChatRun(val chat: ChatId?, val status: StatusUi)

/**
 * The chat screen's state. Each chat's thread and task live in a [ChatSession]; this holds the one on screen, the
 * ones still working after the user opened another, and what is shared across chats: the model, the composer, the
 * list of chats and app installs.
 *
 * The app owns one of these for its whole life, so a task keeps going when the user switches chats, leaves the
 * screen or swipes the app away, and the chat shows it live again when they come back to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val graph: AppGraph,
    /** Starts the app-building setup as a foreground service (the app passes the real one). */
    private val startToolchainSetup: () -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow(ChatState())

    private val _haptics = MutableSharedFlow<WhittleHaptic>(extraBufferCapacity = HAPTIC_BUFFER)
    val haptics: SharedFlow<WhittleHaptic> = _haptics.asSharedFlow()

    private val _events = MutableSharedFlow<ChatEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Moments worth telling the user about when they aren't looking, e.g. as a notification, from any chat. */
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val _attachmentProblems = MutableSharedFlow<AttachmentProblem>(extraBufferCapacity = EVENT_BUFFER)

    /** Why something the user picked wasn't added. */
    val attachmentProblems: SharedFlow<AttachmentProblem> = _attachmentProblems.asSharedFlow()

    private val saveLock = Mutex()

    private val sessionListener = object : ChatSession.Listener {
        override fun haptic(session: ChatSession, haptic: WhittleHaptic) {
            // Only the chat on screen buzzes; the others tell the user through their notifications.
            if (session === current.value) _haptics.tryEmit(haptic)
        }

        override fun event(session: ChatSession, event: ChatEvent) {
            _events.tryEmit(event)
        }

        override fun saved() = refreshChats()

        override fun projectsChanged() = refreshProjects()

        override fun idle(session: ChatSession) {
            // A chat finished in the background is saved; opening it again loads it like any other.
            val id = session.chatId ?: return
            if (session === current.value || background.value[id] !== session) return
            background.update { it - id }
            session.close()
        }
    }

    /** The chat on screen. */
    private val current = MutableStateFlow(newSession(firstKey = 0))

    /** Chats whose task is still working after the user opened another, by id. */
    private val background = MutableStateFlow<Map<ChatId, ChatSession>>(emptyMap())

    val state: StateFlow<ChatState> = combine(_state, current.flatMapLatest { it.state }, background) { shared, session, others ->
        shared.copy(
            items = session.items,
            status = session.status,
            workspaceRevision = session.workspaceRevision,
            currentChat = session.chatId,
            workingElsewhere = others.keys,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatState())

    /** Every chat with a task on its status line, the open one first. */
    val runs: StateFlow<List<ChatRun>> = combine(current, background) { open, others -> listOf(open) + others.values }
        .flatMapLatest { sessions -> combine(sessions.map { it.state }) { states -> states.mapNotNull { s -> s.status?.let { ChatRun(s.chatId, it) } } } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** A task is working in some chat, open or not. */
    val working: Boolean get() = current.value.working || background.value.isNotEmpty()

    /** A project picked in a new chat, before it has an id to keep it under. */
    private var pendingProject: String? = null

    init {
        viewModelScope.launch { graph.connections.model.collect { model -> _state.update { it.copy(model = model) } } }
        // The agent's use_project changes it too, mid-run.
        viewModelScope.launch { graph.chatProjects.projects.collect { syncProject() } }
        restore()
        refreshProjects()
    }

    private fun newSession(firstKey: Long = current.value.keyCount): ChatSession =
        ChatSession(graph, viewModelScope, saveLock, model = { _state.value.model }, listener = sessionListener, firstKey = firstKey)

    /** Sets the chat's project, or the whole workspace for null. A new chat keeps it until its first message. */
    fun chooseProject(project: String?) {
        val chat = current.value.chatId
        if (chat == null) pendingProject = project else graph.chatProjects.choose(WorkspaceId(chat.value), project)
        syncProject()
    }

    /** Makes a project folder and works on it; false when [name] isn't a plain folder name or can't be made. */
    fun createProject(name: String): Boolean {
        val clean = name.trim()
        if (!ChatProjects.isValidName(clean)) return false
        val root = graph.workspaces.folder().getOrNull() ?: return false
        val folder = File(root, clean)
        if (!folder.isDirectory && !folder.mkdirs()) return false
        chooseProject(clean)
        refreshProjects()
        return true
    }

    /** Lists the workspace's projects again, for the picker and a new chat's suggestions. */
    fun refreshProjects() {
        viewModelScope.launch {
            val projects = withContext(Dispatchers.IO) {
                val root = graph.workspaces.folder().getOrNull()
                // Whittle's own folders and hidden ones, such as a tool's scratch space, aren't projects.
                val hidden = graph.filesDefaults.hiddenNames + graph.attachmentDefaults.directoryName
                root?.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name !in hidden && !it.name.startsWith('.') }
                    .map { ProjectFolder(it.name, it.lastModified()) }
                    .sortedByDescending { it.changedAtMillis }
            }
            _state.update { it.copy(projects = projects) }
        }
    }

    private fun syncProject() = _state.update { state ->
        state.copy(project = current.value.chatId?.let { graph.workspaces.projectOf(WorkspaceId(it.value)) } ?: pendingProject)
    }

    override fun onCleared() {
        // The screen is going away, possibly mid-run: keep the run ids so the next launch can offer to continue.
        current.value.close()
        background.value.values.forEach(ChatSession::close)
    }

    fun updateInput(text: String) = _state.update { it.copy(input = text) }

    /**
     * Saves what the user picked, shot or shared in the workspace and adds it to the next message, up to the limit
     * per message. Whatever can't be added is reported on [attachmentProblems].
     */
    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val room = graph.attachmentDefaults.maxPerMessage - _state.value.attachments.size - _state.value.attaching
        if (uris.size > room) _attachmentProblems.tryEmit(AttachmentProblem.TOO_MANY)
        val taken = uris.take(room.coerceAtLeast(0))
        if (taken.isEmpty()) return
        val project = _state.value.project
        _state.update { it.copy(attaching = it.attaching + taken.size) }
        viewModelScope.launch {
            taken.forEach { uri ->
                val imported = graph.attachments.import(uri, project)
                _state.update { state ->
                    val added = (imported as? Imported.Added)?.attachment
                    state.copy(attachments = state.attachments + listOfNotNull(added), attaching = state.attaching - 1)
                }
                if (imported is Imported.Refused) _attachmentProblems.tryEmit(imported.problem)
            }
        }
    }

    /** A share from another app: a new chat with what was shared attached and any words in the composer. */
    fun startShared(shared: SharedItems) {
        newChat()
        shared.text?.let(::updateInput)
        attach(shared.uris)
    }

    /** Attached photos, for thumbnails and the viewer. */
    val images: WorkspaceImages get() = graph.images

    val filesDefaults: FilesDefaults get() = graph.filesDefaults

    val attachmentDefaults: AttachmentDefaults get() = graph.attachmentDefaults

    /** Where the next message's attachments are saved, as the agent sees the workspace. */
    fun uploadsPath(): String = "${graph.linux.workspaceMount}/${AttachmentNames.folder(_state.value.project, graph.attachmentDefaults.directoryName)}"

    /** Takes an attachment off the next message; it was never sent, so its saved copy goes too. */
    fun removeAttachment(attachment: Attachment) {
        _state.update { state -> state.copy(attachments = state.attachments - attachment) }
        graph.appScope.launch { graph.attachments.discard(attachment) }
    }

    /** Starts a task, or adds to the one that's running without stopping it. */
    fun send() = send(pasted = emptyList())

    /** [send], with output the user [pasted] from a card going beside their words as untrusted content. */
    private fun send(pasted: List<ContentBlock.Text>) {
        val state = _state.value
        val text = state.input.trim()
        val attachments = state.attachments
        val model = state.model ?: return
        if (state.attaching > 0 || (text.isEmpty() && attachments.isEmpty())) return
        _state.update { it.copy(input = "", attachments = emptyList()) }
        val forModel = listOf(text, attachmentNote(attachments, model.capabilities.vision)).filter { it.isNotEmpty() }.joinToString(PARAGRAPH)
        // A model that can't see gets the photos only as files, through the note.
        val images = attachments.filter { it.isImage && model.capabilities.vision }.map { it.path }
        claimPendingProject()
        current.value.send(text, model, forModel, images, attachments, pasted)
    }

    /** A project picked before the first message belongs to the chat from now on. */
    private fun claimPendingProject() {
        val project = pendingProject ?: return
        if (current.value.chatId != null) return
        graph.chatProjects.choose(WorkspaceId(current.value.ensureId().value), project)
        pendingProject = null
    }

    /** Tells the model where the attached files are saved, so it can open them with its tools. */
    private fun attachmentNote(attachments: List<Attachment>, vision: Boolean): String {
        if (attachments.isEmpty()) return ""
        val files = attachments.map { "${graph.linux.workspaceMount}/${it.path}" to AttachmentNames.size(it.sizeBytes) }
        return graph.agentMessages.attachments(files, imagesUnseen = !vision && attachments.any { it.isImage })
    }

    /**
     * Starts an automation in a new chat titled [name], unattended: only [preApprovedTools] run without asking.
     * With a [project], the chat is confined to it: its file tools reach nothing else until the user picks another.
     * The chat it leaves is saved first, as when the user opens another. Null while a task is working in any chat.
     */
    suspend fun startAutomation(name: String, prompt: String, preApprovedTools: Set<ToolId>, project: String?): ChatId? {
        val model = _state.value.model
        if (working || model == null) return null
        newChat()
        val session = current.value
        session.automationName = name
        val chat = session.ensureId()
        project?.let { graph.chatProjects.confine(WorkspaceId(chat.value), it) }
        session.start(prompt, model, RunMode.UNATTENDED, preApprovedTools)
        return chat
    }

    /** The user's choice on a card. Applied at the run's next step, or continues a finished run. */
    fun answer(item: ThreadItem.Decision, approve: Boolean) = current.value.answer(item, approve)

    /**
     * The user's choice from the approval card [key]'s notification, applied as a tap on the card would be, in whichever
     * chat it waits. False when the card was answered already or its chat is closed; nothing changes then.
     */
    fun answerDecision(key: String, approve: Boolean): Boolean {
        for (session in listOf(current.value) + background.value.values) {
            val item = waitingDecision(session.state.value.items, key) ?: continue
            session.answer(item, approve)
            return true
        }
        return false
    }

    /** The card stays in the thread with the choice marked; only the first tap counts. */
    fun answer(item: ThreadItem.Question, choice: String) = current.value.answer(item, choice)

    /** Called with the system permission result; either way the paused run continues and hears it. */
    fun answer(item: ThreadItem.Capability, granted: Boolean) = current.value.answer(item, granted)

    private var settingUpAppBuilding: Job? = null
    private val _appBuildingSetup = MutableStateFlow<String?>(null)

    /** The key of the app-building card whose setup is running, or null; the card says so while it runs. */
    val appBuildingSetup: StateFlow<String?> = _appBuildingSetup.asStateFlow()

    /**
     * Allow on the app-building card. Nothing starts until Linux is set up; otherwise the toolchain is set up in a
     * service. The run hears that it's ready, or that setup failed and why, never that the user said no. [needsLinux]
     * and [failed] (given the kind and its raw detail) are the card's words for the two failures. A second tap does
     * nothing, and a card answered meanwhile is left alone.
     */
    fun setUpAppBuilding(item: ThreadItem.Capability, needsLinux: String, failed: (ToolchainFailure, String?) -> String) {
        if (settingUpAppBuilding?.isActive == true) return
        // The card belongs to the chat open now, which keeps its task if the user opens another meanwhile.
        val session = current.value
        settingUpAppBuilding = viewModelScope.launch {
            _appBuildingSetup.value = item.key
            try {
                val linuxReady = graph.linux.state.value is LinuxState.Ready
                val timeout = graph.nativeApps.setupStartTimeoutSeconds.seconds
                when (val outcome = prepareAppBuilding(linuxReady, graph.appToolchain, startToolchainSetup, timeout)) {
                    SetupOutcome.Ready -> session.answer(item, granted = true)
                    SetupOutcome.NeedsLinux -> session.setupFailed(item, needsLinux, graph.agentMessages.appBuildingNeedsLinux)
                    is SetupOutcome.Failed -> session.setupFailed(
                        item,
                        failed(outcome.kind, outcome.detail),
                        setupReasonForModel(graph.agentMessages, outcome.kind, outcome.detail),
                    )
                }
            } finally {
                _appBuildingSetup.value = null
                settingUpAppBuilding = null
            }
        }
    }

    /** Picks up a run the app closed in the middle of. Nothing re-runs: an interrupted tool is reported. */
    fun continueRun(item: ThreadItem.Interrupted) = current.value.continueRun(item)

    /** Stops the task in the chat on screen. */
    fun stop() = current.value.stop()

    /** Stops every task, in every chat: the notification's Stop. */
    fun stopAll() {
        background.value.values.forEach(ChatSession::stop)
        current.value.stop()
    }

    fun toggleSources(key: String) = current.value.toggleSources(key)

    fun toggleSummary(key: String) = current.value.toggleSummary(key)

    /** Starts a fresh chat; a task working in the one on screen keeps going. */
    fun newChat() = switchTo(newSession())

    /** Opens a saved chat, live if its task is still working; a task working in the one on screen keeps going. */
    fun openChat(id: ChatId) {
        if (id == current.value.chatId) return
        background.value[id]?.let { working ->
            background.update { it - id }
            switchTo(working)
            return
        }
        viewModelScope.launch {
            val session = newSession()
            graph.chats.load(id).getOrNull()?.let { decode(it.document) }?.let { session.load(id, it) }
            switchTo(session)
        }
    }

    /**
     * Puts [next] on screen. The chat it replaces keeps working in the background when a task runs there; otherwise it's
     * saved and let go. The composer starts empty: a draft's attachments were never sent, so their saved copies go.
     */
    private fun switchTo(next: ChatSession) {
        val leaving = current.value
        if (leaving !== next) {
            val id = leaving.chatId
            if (leaving.working && id != null) background.update { it + (id to leaving) } else leaving.close()
        }
        _state.value.attachments.forEach { attachment -> graph.appScope.launch { graph.attachments.discard(attachment) } }
        _state.update { it.copy(input = "", attachments = emptyList()) }
        pendingProject = null
        current.value = next
        syncProject()
        refreshProjects()
    }

    /** Removes a chat for good: its messages, its saved runs and its workspace files. */
    fun deleteChat(id: ChatId) {
        background.value[id]?.let { session ->
            background.update { it - id }
            session.discard()
        }
        if (id == current.value.chatId) {
            val deleted = current.value
            current.value = newSession()
            deleted.discard()
            syncProject()
        }
        _state.update { state -> state.copy(chats = state.chats.filterNot { it.id == id }) }
        graph.appScope.launch {
            saveLock.withLock { purge(id) }
            refreshChats()
        }
    }

    /** Settings > Delete all chats: every chat and its saved tasks; the workspace's files stay. */
    fun deleteAllChats() {
        background.value.values.forEach(ChatSession::discard)
        background.value = emptyMap()
        val deleted = current.value
        current.value = newSession()
        deleted.discard()
        syncProject()
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
        // The workspace is shared, so its files stay; only this chat's record of what it changed goes.
        graph.fileChanges.forget(WorkspaceId(id.value))
        graph.chatProjects.forget(WorkspaceId(id.value))
        graph.claudeCode.sessions.forget(WorkspaceId(id.value))
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
        // Tasks can't go on without the key: each is cut off the way an app close would, so it can be continued.
        background.value.values.forEach { it.cutOff(); it.close() }
        background.value = emptyMap()
        current.value.cutOff()
        newChat()
        viewModelScope.launch {
            provider?.let { graph.secrets.delete(SecretKey.providerApiKey(it)) }
            graph.connections.clear()
        }
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

    private val _appStates = MutableStateFlow<Map<String, AppUiState>>(emptyMap())

    /** What each app card shows about its install, by card key; kept here so it survives scrolling. */
    val appStates: StateFlow<Map<String, AppUiState>> = _appStates.asStateFlow()

    private fun updateApp(key: String, change: (AppUiState) -> AppUiState) = _appStates.update { it + (key to change(it[key] ?: AppUiState())) }

    /** The last crash of each app Whittle built, by package; the card shows its own. */
    val appCrashes: StateFlow<Map<String, String>> get() = graph.appCrashes.latest

    fun permissionWords(card: ThreadItem.App): String = permissionWords(card.permissions, graph.nativeApps.permissionWords)

    /** Re-reads the installed version, so the buttons follow installs and removals that happened outside the chat. */
    fun refreshApp(card: ThreadItem.App) {
        viewModelScope.launch {
            val (installed, live) = withContext(Dispatchers.IO) { graph.appInstaller.installedVersion(card.packageName) to graph.appInstaller.liveSessions() }
            updateApp(card.key) { it.copy(installed = installed, installing = stillInstalling(it.installing, installed, card.versionCode, it.sessionId, live)) }
        }
    }

    /**
     * The user tapped Install or Update. Calls [needsAllow] when Android hasn't allowed Whittle to install apps, [confirm] with
     * the powerful declarations the user must see first, or installs at once.
     */
    fun beginInstall(card: ThreadItem.App, needsAllow: () -> Unit, confirm: (List<PowerfulDeclaration>) -> Unit) {
        if (_appStates.value[card.key]?.installing == true) return
        viewModelScope.launch {
            val (can, warn) = withContext(Dispatchers.IO) {
                graph.appInstaller.canInstall() to addedPowerful(card, graph.appInstaller.installedPowerful(card.packageName))
            }
            when {
                !can -> needsAllow()
                warn.isEmpty() -> installApp(card)
                else -> confirm(warn)
            }
        }
    }

    /** Installs the card's own APK after checking it is still what the card showed. A second tap while one runs does nothing. */
    fun installApp(card: ThreadItem.App) {
        if (_appStates.value[card.key]?.installing == true) return
        updateApp(card.key) { it.copy(installing = true, result = null, sessionId = null) }
        graph.appScope.launch {
            var outcome = InstallResult.FAILED
            try {
                val root = (graph.workspaces.folder() as? Outcome.Success)?.value
                val apk = root?.let { withContext(Dispatchers.IO) { resolveApk(it, card.apkPath) } }?.takeIf { it.isFile }
                outcome = if (apk == null) InstallResult.CHANGED else graph.appInstaller.install(card, apk) { id -> updateApp(card.key) { it.copy(sessionId = id) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                outcome = InstallResult.FAILED
            } finally {
                // A newer build is on the phone now, so the last crash was the old one's.
                if (outcome == InstallResult.INSTALLED) graph.appCrashes.clear(card.packageName)
                // Whatever happened, the card leaves installing.
                val installed = withContext(NonCancellable + Dispatchers.IO) { runCatching { graph.appInstaller.installedVersion(card.packageName) }.getOrNull() }
                updateApp(card.key) { it.copy(installing = false, result = outcome, installed = installed, sessionId = null) }
            }
        }
    }

    /** Drops the last install message, when the user starts something else on the card. */
    fun clearAppResult(card: ThreadItem.App) {
        updateApp(card.key) { it.copy(result = null) }
    }

    /** Every process this run of the app knows; a card whose process isn't here isn't running. */
    val processes: StateFlow<List<ProcessInfo>> get() = graph.processes.processes

    fun processOutput(item: ThreadItem.Process, lines: Int): String = graph.processes.output(item.processId, lines).orEmpty()

    fun stopProcess(item: ThreadItem.Process) {
        graph.appScope.launch { graph.processes.stop(item.processId) }
    }

    /** Runs the card's command again, in the same folder; the card follows the new process. */
    fun restartProcess(item: ThreadItem.Process) = current.value.restartProcess(item)

    /** Sends [text] as the user's message, as if typed. */
    fun sendText(text: String) {
        _state.update { it.copy(input = text) }
        send()
    }

    /**
     * Ask to fix on a card: sends [request] as the user's message, which is all the chat shows, with the [output] of the program
     * called [name] (a crash, a log, a console) beside it as untrusted content. The program controls that output's words, so
     * the model reads it as data and the run is tainted, as with web pages and files.
     */
    fun askToFix(request: String, kind: PastedKind, name: String, output: String) {
        val state = _state.value
        // The button is off then ([askToFixReady]); a tap that slips through sends nothing rather than losing the output.
        if (!canAskToFix(state.attaching, state.model != null)) return
        val pasted = pastedOutput(graph.agentMessages, kind, fixName(name), output, graph.maxPastedOutputChars)
        _state.update { it.copy(input = request) }
        send(listOf(pasted))
    }

    /** Whether Ask to fix can send now ([canAskToFix]); the cards turn the button off when it can't. */
    val askToFixReady: StateFlow<Boolean> = _state.map { canAskToFix(it.attaching, it.model != null) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, canAskToFix(_state.value.attaching, _state.value.model != null))

    /** [name] (an app's or a process's, from the workspace) as an Ask to fix request may show it: one short line. */
    fun fixName(name: String): String = requestName(name, graph.maxPastedNameChars)

    /** The card's project folder for its Ask to fix request, or null when it isn't the one build_app checked. */
    fun fixFolder(card: ThreadItem.App): String? = fixProject(card, graph.nativeApps.packagePrefix)

    private fun restore() = viewModelScope.launch {
        val stored = graph.chats.latest().getOrNull()
        val document = stored?.let { decode(it.document) }
        if (stored != null && document != null) current.value.load(stored.id, document)
        syncProject()
        refreshChats()
        _state.update { it.copy(restoring = false) }
    }

    private fun decode(document: String): ChatDocument? = ChatDocument.decode(document)

    private companion object {
        const val HAPTIC_BUFFER = 8
        const val EVENT_BUFFER = 8
        const val PARAGRAPH = "\n\n"
    }
}

/**
 * The user's message as the model gets it: their words ([forModel], with any attachment note), then output they [pasted] from a
 * card as untrusted content ([pastedOutput]), then their photos by workspace path.
 */
internal fun userMessage(forModel: String, pasted: List<ContentBlock.Text>, images: List<String>): Message =
    Message(Role.USER, listOf(ContentBlock.Text(forModel)) + pasted + images.map(ContentBlock::ImageFile))
