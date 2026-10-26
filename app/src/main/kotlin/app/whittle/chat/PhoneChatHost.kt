package app.whittle.chat

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import app.whittle.AppGraph
import app.whittle.chat.ui.PhoneLook
import app.whittle.chat.ui.SchemeColors
import app.whittle.settings.UserSettings
import app.whittle.agent.PastedKind
import app.whittle.agent.PlanItem
import app.whittle.agent.PlanStatus
import app.whittle.apps.InstallResult
import app.whittle.apps.PowerfulDeclaration
import app.whittle.attachments.Attachment
import app.whittle.attachments.AttachmentProblem as PhoneAttachmentProblem
import app.whittle.chat.ui.AttachmentEntry
import app.whittle.chat.ui.AttachmentProblem
import app.whittle.chat.ui.CardText
import app.whittle.chat.ui.ChatAction
import app.whittle.chat.ui.ChatEntry
import app.whittle.chat.ui.ChatEvent
import app.whittle.chat.ui.ChatHost
import app.whittle.chat.ui.ChatLimits
import app.whittle.chat.ui.ChatScreenState
import app.whittle.chat.ui.InstallOutcome
import app.whittle.chat.ui.PlanRow
import app.whittle.chat.ui.PlanRowState
import app.whittle.chat.ui.PowerRow
import app.whittle.chat.ui.ProcessRun
import app.whittle.chat.ui.ProjectEntry
import app.whittle.chat.ui.RunState
import app.whittle.chat.ui.RunStatus
import app.whittle.chat.ui.ScreenEntry
import app.whittle.chat.ui.SourceRow
import app.whittle.chat.ui.StepRow
import app.whittle.chat.ui.StepRowStatus
import app.whittle.chat.ui.Suggestion
import app.whittle.chat.ui.ThreadRow
import app.whittle.design.components.AgentState
import app.whittle.design.theme.ThemeMode
import app.whittle.storage.chats.ChatId
import app.whittle.tools.presentation.CapabilityPresentation
import app.whittle.tools.presentation.DecisionText
import app.whittle.tools.workspace.ProcessInfo
import app.whittle.tools.workspace.ProcessStatus
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import app.whittle.chat.ComposerStart as PhoneComposerStart
import app.whittle.chat.ui.ComposerStart as ScreenComposerStart
import app.whittle.chat.ui.NoticeKind as ScreenNoticeKind

/**
 * The phone's chats as the chat screen draws them, and its taps carried out: for the phone's own screen and for the
 * user's computers alike, so both show and do the same. Lives as long as the app, like [ChatViewModel].
 */
class PhoneChatHost(private val chat: ChatViewModel, private val graph: AppGraph, private val context: Context, scope: CoroutineScope) : ChatHost {
    /** Whether the phone itself is in dark mode, kept current as the user or the time of day switches it. */
    private val night = MutableStateFlow(context.resources.configuration.isNight)

    init {
        context.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(configuration: Configuration) {
                night.value = configuration.isNight
            }

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
    }

    override val state: StateFlow<ChatScreenState> = combine(
        chat.state,
        graph.processes.processes,
        combine(chat.appStates, chat.appCrashes, ::Pair),
        combine(chat.askToFixReady, chat.appBuildingSetup, ::Pair),
        combine(graph.currentSettings, night, ::Pair),
    ) { state, processes, (apps, crashes), (askReady, settingUp), (settings, night) ->
        screenState(state, processes, apps, crashes, askReady, settingUp, settings?.avatarSeed).copy(look = look(settings, night))
    }.stateIn(scope, SharingStarted.Eagerly, ChatScreenState())

    override val events: Flow<ChatEvent> = merge(
        chat.haptics.map { ChatEvent.Haptic(it) },
        chat.attachmentProblems.map { ChatEvent.AttachmentRefused(it.screen) },
    )

    override fun act(action: ChatAction) {
        val items = chat.state.value.items
        when (action) {
            is ChatAction.UpdateInput -> chat.updateInput(action.text)
            ChatAction.Send -> chat.send()
            is ChatAction.SendText -> chat.sendText(action.text)
            is ChatAction.UseIdea -> chat.useIdea(action.prompt)
            ChatAction.ComposerStarted -> chat.composerStarted()
            ChatAction.PreviewOpened -> chat.previewOpened()
            is ChatAction.Answer -> items.find<ThreadItem.Decision>(action.key)?.let { chat.answer(it, action.approve) }
            is ChatAction.Pick -> items.find<ThreadItem.Question>(action.key)
                ?.takeIf { it.answer == null && action.option in it.text.options }?.let { chat.answer(it, action.option) }
            is ChatAction.Grant -> items.find<ThreadItem.Capability>(action.key)?.let { chat.answer(it, action.granted) }
            is ChatAction.Continue -> items.find<ThreadItem.Interrupted>(action.key)?.let(chat::continueRun)
            ChatAction.Stop -> chat.stop()
            is ChatAction.ToggleSummary -> chat.toggleSummary(action.key)
            is ChatAction.ToggleSources -> chat.toggleSources(action.key)
            ChatAction.NewChat -> chat.newChat()
            is ChatAction.OpenChat -> chat.openChat(ChatId(action.id))
            is ChatAction.SetPinned -> chat.setPinned(ChatId(action.id), action.pinned)
            is ChatAction.DeleteChat -> chat.deleteChat(ChatId(action.id))
            is ChatAction.ChooseProject -> chat.chooseProject(action.project)
            ChatAction.RefreshProjects -> chat.refreshProjects()
            ChatAction.RemoveScreen -> chat.removeScreen()
            is ChatAction.RemoveAttachment -> chat.state.value.attachments.firstOrNull { it.path == action.path }?.let(chat::removeAttachment)
            is ChatAction.StopProcess -> items.find<ThreadItem.Process>(action.key)?.let(chat::stopProcess)
            is ChatAction.RestartProcess -> items.find<ThreadItem.Process>(action.key)?.let(chat::restartProcess)
            is ChatAction.FixProcess -> items.find<ThreadItem.Process>(action.key)?.let { card ->
                chat.askToFix(action.request, PastedKind.PROCESS_OUTPUT, card.name, chat.processOutput(card, FIX_LINES))
            }
            is ChatAction.FixApp -> items.find<ThreadItem.App>(action.key)?.let { card ->
                chat.appCrashes.value[card.packageName]?.let { crash -> chat.askToFix(action.request, PastedKind.APP_CRASH, card.name, crash) }
            }
            is ChatAction.RefreshApp -> items.find<ThreadItem.App>(action.key)?.let(chat::refreshApp)
            is ChatAction.ClearAppResult -> items.find<ThreadItem.App>(action.key)?.let(chat::clearAppResult)
        }
    }

    override suspend fun createProject(name: String): Boolean = withContext(Dispatchers.Main) { chat.createProject(name) }

    override suspend fun processOutput(key: String, lines: Int): String =
        chat.state.value.items.find<ThreadItem.Process>(key)?.let { chat.processOutput(it, lines) }.orEmpty()

    override suspend fun image(path: String, maxSidePx: Int?): ImageBitmap? =
        if (maxSidePx == null) graph.images.thumbnail(path) else graph.images.full(path, maxSidePx)

    /**
     * How the phone looks now: light or dark as it shows itself ([night] when the user lets it follow the phone), and its
     * wallpaper's colours when they're on (Android 12+).
     */
    private fun look(settings: UserSettings?, night: Boolean): PhoneLook {
        val dark = when (settings?.themeMode ?: graph.defaultAppearance.themeMode) {
            ThemeMode.System -> night
            ThemeMode.Light -> false
            ThemeMode.Dark -> true
        }
        val wallpaper = (settings?.wallpaperColors ?: graph.defaultAppearance.wallpaperColors) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        if (!wallpaper) return PhoneLook(dark)
        return PhoneLook(dark, SchemeColors.of(dynamicLightColorScheme(context)), SchemeColors.of(dynamicDarkColorScheme(context)))
    }

    private val Configuration.isNight: Boolean get() = uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** The card [key] of type [T] in the thread, inside a finished run's summary too. */
    private inline fun <reified T : ThreadItem> List<ThreadItem>.find(key: String): T? = everything().firstOrNull { it.key == key } as? T

    private fun List<ThreadItem>.everything(): Sequence<ThreadItem> =
        asSequence().flatMap { item -> if (item is ThreadItem.Summary) sequenceOf(item) + item.folded.everything() else sequenceOf(item) }

    private fun screenState(
        state: ChatState,
        processes: List<ProcessInfo>,
        apps: Map<String, AppUiState>,
        crashes: Map<String, String>,
        askReady: Boolean,
        settingUp: String?,
        avatarSeed: Long?,
    ): ChatScreenState {
        val rows = Rows(processes.associateBy { it.id }, apps, crashes)
        return ChatScreenState(
            restoring = state.restoring,
            chats = state.chats.map { ChatEntry(it.id.value, it.title, it.updatedAtMillis, it.pinned) },
            currentChat = state.currentChat?.value,
            workingElsewhere = state.workingElsewhere.mapTo(mutableSetOf()) { it.value },
            items = state.items.map(rows::of),
            status = state.status?.let { status ->
                RunStatus(
                    state = when (status.state) {
                        AgentState.Thinking -> RunState.Thinking
                        AgentState.Acting -> RunState.Acting
                        AgentState.Done -> RunState.Done
                    },
                    verb = status.verb,
                    startedAtMillis = status.startedAtMillis,
                    plan = status.plan?.map(::planRow),
                    retrying = status.phase == StatusPhase.Retrying,
                )
            },
            input = state.input,
            model = state.model?.displayName,
            imagesUnseen = state.imagesUnseen,
            project = state.project,
            projects = state.projects.map { ProjectEntry(it.name, it.changedAtMillis) },
            attachments = state.attachments.map(::attachment),
            attaching = state.attaching,
            screen = state.screen?.let { ScreenEntry(it.appName) },
            composerStart = when (state.composerStart) {
                PhoneComposerStart.TYPE -> ScreenComposerStart.Type
                PhoneComposerStart.LISTEN -> ScreenComposerStart.Listen
                null -> null
            },
            openPreview = state.openPreview,
            starters = chat.starters.map { Suggestion(it.icon, it.title, it.prompt) },
            appIdeas = chat.appIdeas.map { Suggestion(it.icon, it.title, it.prompt) },
            askToFixReady = askReady,
            appBuildingSetup = settingUp,
            avatarSeed = avatarSeed,
            uploadsPath = chat.uploadsPath(),
            limits = ChatLimits(
                sidebarMaxChats = graph.sidebarMaxChats,
                recentProjects = graph.projectDefaults.recentCount,
                maxAttachments = graph.attachmentDefaults.maxPerMessage,
                maxAttachmentBytes = graph.attachmentDefaults.maxFileBytes,
                viewerImageSidePx = graph.filesDefaults.maxImageSidePx,
                previewHeight = graph.previewSheetHeight,
            ),
        )
    }

    /** Thread items as rows, with what the cards show live: their processes, installs and crashes. */
    private inner class Rows(
        private val processes: Map<String, ProcessInfo>,
        private val apps: Map<String, AppUiState>,
        private val crashes: Map<String, String>,
    ) {
        fun of(item: ThreadItem): ThreadRow = when (item) {
            is ThreadItem.User -> ThreadRow.User(item.key, item.text, item.steer, item.attachments.map(::attachment))
            is ThreadItem.Narration -> ThreadRow.Narration(item.key, item.text, item.streaming, item.answer)
            is ThreadItem.Steps -> ThreadRow.Steps(item.key, item.steps.map(::step))
            is ThreadItem.Decision -> ThreadRow.Decision(item.key, card(item.text))
            is ThreadItem.Question -> ThreadRow.Question(item.key, item.text.icon, item.text.question, item.text.options, item.answer)
            is ThreadItem.Capability -> ThreadRow.Capability(item.key, item.capability.value, card(item.presentation))
            is ThreadItem.Resolved -> ThreadRow.Resolved(item.key, item.outcome, item.declined)
            is ThreadItem.Summary -> ThreadRow.Summary(item.key, item.stepCount, item.seconds, item.expanded, item.folded.map(::of))
            is ThreadItem.Plan -> ThreadRow.Plan(item.key, item.steps.map(::planRow))
            is ThreadItem.Process -> ThreadRow.Process(item.key, item.name, item.command, chat.fixName(item.name), processes[item.processId]?.let(::run))
            is ThreadItem.App -> {
                val shown = apps[item.key] ?: AppUiState()
                ThreadRow.App(
                    key = item.key,
                    name = item.name,
                    versionCode = item.versionCode,
                    powerful = item.powerful.map(::power),
                    installed = shown.installed,
                    installing = shown.installing,
                    result = shown.result?.let(::outcome),
                    crash = crashes[item.packageName],
                    fixName = chat.fixName(item.name),
                    fixFolder = chat.fixFolder(item),
                )
            }
            is ThreadItem.Sources -> ThreadRow.Sources(item.key, item.sources.map { SourceRow(it.url, it.title, it.host) }, item.expanded)
            is ThreadItem.Notice -> ThreadRow.Notice(item.key, ScreenNoticeKind.valueOf(item.kind.name), item.statusCode, item.detail, item.limit, item.resetsAtMillis)
            is ThreadItem.Interrupted -> ThreadRow.Interrupted(item.key)
        }
    }

    private fun attachment(attachment: Attachment) = AttachmentEntry(
        path = attachment.path,
        name = attachment.name,
        sizeBytes = attachment.sizeBytes,
        isImage = attachment.isImage,
        icon = graph.filesDefaults.iconFor(attachment.name, directory = false),
    )

    private fun step(step: StepModel) = StepRow(
        id = step.id,
        icon = step.icon,
        label = step.label,
        status = StepRowStatus.valueOf(step.status.name),
        inspection = step.inspection,
        site = step.site,
        file = step.file,
    )

    private fun card(text: DecisionText) =
        CardText(text.icon, text.title, text.body, text.payloadLabel, text.payload, text.approve, text.decline, text.site)

    private fun card(presentation: CapabilityPresentation) =
        CardText(presentation.icon, presentation.title, presentation.body, approve = presentation.approve, decline = presentation.decline)

    private fun planRow(item: PlanItem) = PlanRow(
        item.content,
        when (item.status) {
            PlanStatus.COMPLETED -> PlanRowState.Done
            PlanStatus.IN_PROGRESS -> PlanRowState.Active
            PlanStatus.PENDING -> PlanRowState.Pending
        },
    )

    private fun run(info: ProcessInfo) = ProcessRun(
        starting = info.status == ProcessStatus.Starting,
        running = info.status == ProcessStatus.Running,
        exited = info.status == ProcessStatus.Exited,
        exitCode = info.exitCode,
        port = info.port,
        listening = info.listening,
        startedAtMillis = info.startedAtMillis,
        endedAtMillis = info.endedAtMillis,
    )

    private fun power(declaration: PowerfulDeclaration) = PowerRow(declaration.name, declaration.kind.name, declaration.sentence)

    private fun outcome(result: InstallResult) = when (result) {
        InstallResult.INSTALLED -> InstallOutcome.Installed
        InstallResult.CANCELLED -> InstallOutcome.Cancelled
        InstallResult.KEY_CHANGED -> InstallOutcome.KeyChanged
        InstallResult.BLOCKED -> InstallOutcome.Blocked
        InstallResult.FAILED -> InstallOutcome.Failed
        InstallResult.CHANGED -> InstallOutcome.Changed
    }

    private val PhoneAttachmentProblem.screen: AttachmentProblem
        get() = when (this) {
            PhoneAttachmentProblem.TOO_MANY -> AttachmentProblem.TooMany
            PhoneAttachmentProblem.TOO_LARGE -> AttachmentProblem.TooLarge
            PhoneAttachmentProblem.UNREADABLE -> AttachmentProblem.Unreadable
            PhoneAttachmentProblem.NO_WORKSPACE -> AttachmentProblem.NoWorkspace
        }

    private companion object {
        /** Output lines an Ask to fix on a process card sends beside the user's words. */
        const val FIX_LINES = 40
    }
}
