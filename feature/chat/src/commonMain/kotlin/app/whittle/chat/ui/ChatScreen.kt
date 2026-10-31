package app.whittle.chat.ui

import app.whittle.design.window.userContent
import app.whittle.design.window.BackHandler
import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.WindowTopBar
import app.whittle.design.window.plainTextClip
import app.whittle.files.ui.sizeText
import app.whittle.files.ui.nowMillis
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
import app.whittle.design.components.ReadingWidth
import app.whittle.design.components.ReadingLazyColumn
import app.whittle.design.components.readingInset
import app.whittle.design.components.AgentState
import app.whittle.design.components.AnswerFooter
import app.whittle.design.components.AnswerFooterUi
import app.whittle.design.components.AnswerSources
import app.whittle.design.components.Composer
import app.whittle.design.components.ComposerAttach
import app.whittle.design.components.DecisionCard
import app.whittle.design.components.DecisionUi
import app.whittle.design.components.LatestPill
import app.whittle.design.components.MenuAction
import app.whittle.design.components.Narration
import app.whittle.design.components.PlanChecklist
import app.whittle.design.components.PlanStepState
import app.whittle.design.components.PlanStepUi
import app.whittle.design.components.QuestionCard
import app.whittle.design.components.QuestionUi
import app.whittle.design.components.ResolvedChip
import app.whittle.design.components.RunSummaryChip
import app.whittle.design.components.SourceUi
import app.whittle.design.components.StatusLine
import app.whittle.design.components.StatusPlanUi
import app.whittle.design.components.StepGroup
import app.whittle.design.components.StepStatus
import app.whittle.design.components.StepUi
import app.whittle.design.components.TopBarAction
import app.whittle.design.components.TopBarActionGroup
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.TopBarGroupAction
import app.whittle.design.components.UserMessage
import app.whittle.design.components.WelcomeMascot
import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.WhittleTransitions
import app.whittle.design.components.connectedShape
import app.whittle.design.components.lightMarkdown
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleMotion
import app.whittle.design.tokens.WhittleSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The chat: the thread, the dock (status line, project, attachments and composer) and the chat list in a drawer. It draws
 * [ChatHost.state] and sends taps to [ChatHost.act]; what only a device can do goes through [platform].
 */
@Composable
fun ChatScreen(host: ChatHost, platform: ChatPlatform) {
    val state by host.state.collectAsState()
    val act = host::act
    val navigation = platform.navigation
    var previewing by remember { mutableStateOf<String?>(null) }
    previewing?.let { key ->
        // The card may have restarted its process since; the preview follows the card.
        (state.items.firstOrNull { it.key == key } as? ThreadRow.Process)?.let { platform.Preview(it, onDismiss = { previewing = null }) }
    }
    // An app's home-screen shortcut asked for its preview.
    LaunchedEffect(state.openPreview) {
        val key = state.openPreview ?: return@LaunchedEffect
        if (state.items.any { it.key == key && it is ThreadRow.Process }) previewing = key
        act(ChatAction.PreviewOpened)
    }
    val chat = state.currentChat
    val openFile = navigation.openFile?.let { open -> { path: String -> open(path, chat) } }
    // A photo opens in the viewer beside the others it came with; any other file in the file viewer.
    var viewing by remember { mutableStateOf<Pair<List<AttachmentEntry>, AttachmentEntry>?>(null) }
    viewing?.let { (photos, start) ->
        PhotoViewer(
            photos = photos,
            start = start,
            maxSidePx = state.limits.viewerImageSidePx,
            onShowInFiles = openFile?.let { open -> { photo -> viewing = null; open(photo.path) } },
            onShare = platform.share?.let { share -> { photo -> share(photo.path, photo.name) } },
            onDismiss = { viewing = null },
        )
    }
    CompositionLocalProvider(
        LocalWorkspaceImages provides WorkspaceImageLoader(host::image),
        LocalOpenPreview provides { item -> previewing = item.key },
        LocalOpenFile provides openFile,
        LocalOpenAttachment provides { group, attachment ->
            if (attachment.isImage) viewing = group.filter { it.isImage } to attachment else openFile?.invoke(attachment.path)
        },
    ) {
        ChatLayout(state, host, platform, act)
    }
}

/** Opens one of a message's attachments, given all of them; provided by [ChatScreen]. */
private val LocalOpenAttachment = staticCompositionLocalOf<(List<AttachmentEntry>, AttachmentEntry) -> Unit> { { _, _ -> } }

/** Opens a process card's live preview; provided by [ChatScreen] so any thread row can use it. */
private val LocalOpenPreview = staticCompositionLocalOf<(ThreadRow.Process) -> Unit> { {} }

/** Opens a file in the workspace by its path there, such as one a step wrote; null where the file browser isn't here. */
private val LocalOpenFile = staticCompositionLocalOf<((String) -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatLayout(state: ChatScreenState, host: ChatHost, platform: ChatPlatform, act: (ChatAction) -> Unit) {
    val haptics = WhittleTheme.haptics
    val navigation = platform.navigation
    val problems = attachmentProblemMessages(state.limits)
    LaunchedEffect(host) {
        host.events.collect { event ->
            when (event) {
                is ChatEvent.Haptic -> haptics.perform(event.haptic)
                is ChatEvent.AttachmentRefused -> platform.toast(problems.getValue(event.problem))
            }
        }
    }
    var attaching by rememberSaveable { mutableStateOf(false) }
    val sources = platform.rememberAttachSources()
    if (attaching) {
        AttachSheet(
            uploadsPath = state.uploadsPath,
            maxAttachments = state.limits.maxAttachments,
            maxAttachmentBytes = state.limits.maxAttachmentBytes,
            sources = sources,
            onDismiss = { attaching = false },
        )
    }
    var choosingProject by rememberSaveable { mutableStateOf(false) }
    if (choosingProject) {
        ProjectSheet(
            current = state.project,
            projects = state.projects,
            onChoose = { act(ChatAction.ChooseProject(it)) },
            onCreate = host::createProject,
            onDismiss = { choosingProject = false },
        )
    }
    val requestCapability = platform.rememberCapabilityRequest()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Back closes the chat list before it leaves the app.
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    // Opening the chat list puts the keyboard away; it belongs to the composer behind it.
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(drawer.targetValue) {
        if (drawer.targetValue == DrawerValue.Open) {
            keyboard?.hide()
            focus.clearFocus()
        }
    }
    val closeThen = { action: () -> Unit -> scope.launch { drawer.close() }; action() }
    val title = state.chats.firstOrNull { it.id == state.currentChat }?.title
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ChatSidebar(
                chats = state.chats,
                current = state.currentChat,
                working = state.workingElsewhere,
                maxChats = state.limits.sidebarMaxChats,
                onNewChat = { closeThen { act(ChatAction.NewChat) } },
                onOpen = { id -> closeThen { act(ChatAction.OpenChat(id)) } },
                onPin = { id, pinned -> act(ChatAction.SetPinned(id, pinned)) },
                onDelete = { act(ChatAction.DeleteChat(it)) },
                onViewAll = navigation.viewAllChats?.let { view -> { closeThen(view) } },
                onOpenSettings = navigation.openSettings?.let { open -> { closeThen(open) } },
                avatarSeed = state.avatarSeed,
                onOpenProjects = navigation.openProjects?.let { open -> { closeThen(open) } },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                WindowTopBar {
                    TopAppBar(
                        windowInsets = LocalWindowChrome.current?.insets ?: TopAppBarDefaults.windowInsets,
                        navigationIcon = {
                            TopBarButton(
                                icon = "short_text",
                                contentDescription = stringResource(Res.string.sidebar_open),
                                onClick = { scope.launch { drawer.open() } },
                                modifier = Modifier.padding(start = WhittleSpace.m),
                            )
                        },
                        title = {
                            Text(
                                title ?: stringResource(Res.string.chat_title),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.userContent(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        actions = { TopBarActions(state, navigation, act) },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                }
            },
        ) { padding ->
            // The dock floats over the end of the thread, so only its status card, chip and composer hide what's behind
            // them; the thread leaves room for the dock at its bottom, so nothing ends up under it for good.
            var dockHeight by remember { mutableIntStateOf(0) }
            val dockSpace = with(LocalDensity.current) { dockHeight.toDp() }
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                val changeModel = navigation.changeModel
                val unseen: (@Composable (Modifier) -> Unit)? = if (state.imagesUnseen) {
                    { modifier -> ImagesUnseenNote(state.model.orEmpty(), changeModel, modifier) }
                } else {
                    null
                }
                when {
                    state.restoring -> Unit
                    // Before the first message, the note stands where the welcome would.
                    state.items.isEmpty() && unseen != null -> Box(Modifier.fillMaxSize().padding(bottom = dockSpace), contentAlignment = Alignment.Center) { unseen(Modifier) }
                    state.items.isEmpty() -> EmptyThread(state, act, modifier = Modifier.padding(bottom = dockSpace))
                    // One list per chat: switching replaces the thread instead of animating one chat into another.
                    else -> key(state.currentChat) { Thread(RowContext(state, host, platform, act, requestCapability), dockSpace, unseen) }
                }
                Dock(
                    state,
                    platform,
                    act,
                    onChooseProject = { act(ChatAction.RefreshProjects); choosingProject = true },
                    onAttach = if (sources.isEmpty()) null else { { attaching = true } },
                    modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { dockHeight = it.height },
                )
            }
        }
    }
}

/** Files (where the file browser is here), new chat and the overflow menu, as one connected group. */
@Composable
private fun TopBarActions(state: ChatScreenState, navigation: ChatNavigation, act: (ChatAction) -> Unit) {
    val openFolder = navigation.openFolder
    val count = if (openFolder != null) TOP_BAR_ACTIONS else TOP_BAR_ACTIONS - 1
    val newChatIndex = if (openFolder != null) 1 else 0
    TopBarActionGroup(Modifier.padding(end = WhittleSpace.m)) {
        if (openFolder != null) {
            TopBarAction(
                icon = "folder_open",
                contentDescription = stringResource(Res.string.files_open),
                onClick = { openFolder(state.project.orEmpty(), state.currentChat) },
                shape = connectedShape(0, count),
                modifier = TopBarGroupAction,
            )
        }
        TopBarAction(
            icon = "edit_square",
            contentDescription = stringResource(Res.string.sidebar_new_chat),
            onClick = { act(ChatAction.NewChat) },
            shape = connectedShape(newChatIndex, count),
            modifier = TopBarGroupAction,
        )
        val current = state.chats.firstOrNull { it.id == state.currentChat }
        ChatMenu(
            pinned = current?.pinned,
            onPin = { pinned -> current?.let { act(ChatAction.SetPinned(it.id, pinned)) } },
            onDelete = { current?.let { act(ChatAction.DeleteChat(it.id)) } },
            shape = connectedShape(count - 1, count),
        )
    }
}

/** What every row in the thread may need: the state, where actions go and what the device can do. */
private class RowContext(
    val state: ChatScreenState,
    val host: ChatHost,
    val platform: ChatPlatform,
    val act: (ChatAction) -> Unit,
    val requestCapability: ((ThreadRow.Capability) -> Unit)?,
)

/**
 * Reads top to bottom like a document. While the user is at the bottom the view follows new content
 * as it streams; scrolling up stops following, and the Latest pill brings them back.
 */
@Composable
private fun Thread(
    rows: RowContext,
    /** The height of the dock floating over the thread's end, kept clear below the last item. */
    dockSpace: Dp,
    /** A note centred at the thread's end, such as the model not seeing an attached photo. */
    footer: (@Composable (Modifier) -> Unit)? = null,
) {
    val items = rows.state.items
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScrollForward -> if (!canScrollForward) following = true }
    }
    // Changes whenever anything new appears or the last item changes at all: streaming text, a step
    // added to a group, a card resolving. While following, each change keeps the bottom in view.
    // The dock growing (a status card appearing) also moves the bottom, so it keeps it in view too.
    LaunchedEffect(items.size, items.lastOrNull(), dockSpace, footer != null) {
        if (following && items.isNotEmpty()) listState.scrollToItem(items.lastIndex + if (footer != null) 1 else 0, scrollOffset = Int.MAX_VALUE)
    }
    Box(Modifier.fillMaxSize()) {
        // The thread reads in a centred column on a wide window; the whole width still scrolls.
        ReadingLazyColumn(
            minInset = WhittleSpace.l,
            modifier = Modifier.fillMaxSize(),
            state = listState,
            top = WhittleSpace.l,
            bottom = WhittleSpace.l + dockSpace,
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
        ) {
            // One footer per turn, under its last answer; the turn's sources join it instead of a row of their own.
            // A plan lives in the status card while it runs; afterwards it's in the run's summary.
            val plan = AnswerFooters.plan(items)
            items(items.filterNot { it.key in plan.folded || it is ThreadRow.Plan }, key = { it.key }) { item ->
                ThreadRowView(item, rows, Modifier.animateItem(), plan.footers[item.key])
            }
            footer?.let { note ->
                item(key = FOOTER_KEY) {
                    Box(Modifier.fillMaxWidth().animateItem().padding(vertical = WhittleSpace.xxl), contentAlignment = Alignment.Center) { note(Modifier) }
                }
            }
        }
        AnimatedVisibility(
            visible = !following && listState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = WhittleSpace.s + dockSpace),
            // A small surface: the fast spatial spring gives it Expressive's slight overshoot as it lands.
            enter = WhittleTransitions.riseIn(WhittleMotion.spatialFast),
            exit = WhittleTransitions.sinkOut(),
        ) {
            LatestPill(stringResource(Res.string.thread_latest), working = rows.state.running) {
                following = true
                scope.launch { listState.animateScrollToItem(items.lastIndex, scrollOffset = Int.MAX_VALUE) }
            }
        }
    }
}

@Composable
private fun ThreadRowView(item: ThreadRow, rows: RowContext, modifier: Modifier = Modifier, footer: TurnFooter? = null) {
    val act = rows.act
    // What the user and the agent wrote, did and made stays out of session recordings; notices don't.
    val content = modifier.userContent()
    when (item) {
        is ThreadRow.User -> if (item.attachments.isEmpty()) {
            UserMessage(item.text, content, steerLabel = if (item.steer) stringResource(Res.string.steer_label) else null)
        } else {
            val open = LocalOpenAttachment.current
            Column(content, verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                MessageAttachments(item.attachments, onOpen = { open(item.attachments, it) })
                if (item.text.isNotEmpty() || item.steer) {
                    UserMessage(item.text, steerLabel = if (item.steer) stringResource(Res.string.steer_label) else null)
                }
            }
        }
        is ThreadRow.Narration -> if (footer != null) {
            Answer(item, footer, act, content)
        } else {
            Narration(item.text, item.streaming, content)
        }
        is ThreadRow.Steps -> StepGroup(item.steps.map { it.toUi() }, content)
        is ThreadRow.Decision -> DecisionCard(
            item.card.toUi(item.key),
            onApprove = { act(ChatAction.Answer(item.key, approve = true)) },
            onDecline = { act(ChatAction.Answer(item.key, approve = false)) },
            modifier = content,
        )
        is ThreadRow.Question -> QuestionCard(
            QuestionUi(item.key, item.icon, item.question, item.options),
            onPick = { act(ChatAction.Pick(item.key, it)) },
            modifier = content,
            answer = item.answer,
        )
        is ThreadRow.Capability -> CapabilityCard(item, rows, modifier)
        is ThreadRow.Interrupted -> Row(
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        ) {
            Text(
                stringResource(Res.string.notice_interrupted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { act(ChatAction.Continue(item.key)) }) { Text(stringResource(Res.string.notice_continue)) }
        }
        is ThreadRow.Resolved -> ResolvedChip(item.outcome, item.declined, content)
        is ThreadRow.Summary -> Column(modifier, verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            RunSummaryChip(
                text = pluralStringResource(Res.plurals.run_summary, item.stepCount, item.stepCount, item.seconds),
                expanded = item.expanded,
                onToggle = { act(ChatAction.ToggleSummary(item.key)) },
            )
            AnimatedVisibility(item.expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                    item.folded.forEach { folded -> ThreadRowView(folded, rows) }
                }
            }
        }
        is ThreadRow.Process -> ProcessCard(
            item,
            askReady = rows.state.askToFixReady,
            onAction = act,
            onPreview = LocalOpenPreview.current,
            output = rows.host::processOutput,
            modifier = content,
        )
        is ThreadRow.App -> AppCard(item, askReady = rows.state.askToFixReady, onAction = act, installs = rows.platform.apps, modifier = content)
        is ThreadRow.Plan -> {
            val (shown, hidden) = planShown(item.steps)
            PlanChecklist(
                title = stringResource(Res.string.plan_title, item.steps.count { it.state == PlanRowState.Done }, item.steps.size),
                steps = shown.map { it.toUi() },
                modifier = content,
                more = hidden.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.plan_more, it, it) },
            )
        }
        is ThreadRow.Sources -> {
            val uriHandler = LocalUriHandler.current
            AnswerSources(
                label = pluralStringResource(Res.plurals.sources_count, item.sources.size, item.sources.size),
                sources = item.sources.map { SourceUi(it.title, it.host, it.url) },
                expanded = item.expanded,
                onToggle = { act(ChatAction.ToggleSources(item.key)) },
                onOpen = { uriHandler.openUri(it.url) },
                modifier = content,
            )
        }
        is ThreadRow.Notice -> Text(
            noticeText(item),
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.kind.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A card asking for a capability. Allow is the device's own prompt; where there is none, the card says it's on the phone. */
@Composable
private fun CapabilityCard(item: ThreadRow.Capability, rows: RowContext, modifier: Modifier) {
    val settingUp = rows.state.appBuildingSetup == item.key
    val request = rows.requestCapability
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        DecisionCard(
            item.card.toUi(item.key),
            onApprove = { request?.invoke(item) },
            onDecline = { rows.act(ChatAction.Grant(item.key, granted = false)) },
            // Setup is under way; its end answers the card.
            declineEnabled = !settingUp,
        )
        val note = when {
            settingUp -> stringResource(Res.string.app_building_setting_up)
            request == null -> stringResource(Res.string.capability_on_phone)
            else -> null
        }
        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = WhittleSpace.m),
            )
        }
    }
}

@Composable
private fun Dock(
    state: ChatScreenState,
    platform: ChatPlatform,
    act: (ChatAction) -> Unit,
    onChooseProject: () -> Unit,
    /** Opens the attach sheet; null where nothing can be attached. */
    onAttach: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val voice = platform.rememberVoice(state.input) { act(ChatAction.UpdateInput(it)) }
    val composerFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.composerStart) {
        val start = state.composerStart ?: return@LaunchedEffect
        act(ChatAction.ComposerStarted)
        when (start) {
            ComposerStart.Type -> {
                composerFocus.requestFocus()
                keyboard?.show()
            }
            ComposerStart.Listen -> if (voice != null && !voice.listening) voice.onToggle()
        }
    }
    val openAttachment = LocalOpenAttachment.current
    // No background of its own: only the cards, the chip and the composer cover the thread behind them. On a wide window
    // it lines up with the thread's column.
    ReadingWidth(WhittleSpace.m, modifier.fillMaxWidth()) { inset ->
        Column(
            Modifier.fillMaxWidth().padding(start = inset, end = inset, bottom = WhittleSpace.s),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        ) {
            AnimatedVisibility(state.status != null, enter = WhittleTransitions.riseInPlace(), exit = WhittleTransitions.sinkOutPlace()) {
                state.status?.let { status ->
                    StatusLine(
                        // A plan step under way is work, even while the model thinks between its actions.
                        state = if (status.plan?.any { it.state == PlanRowState.Active } == true) AgentState.Acting else status.state.agentState,
                        verb = status.verb ?: stringResource(
                            when {
                                status.retrying -> Res.string.status_retrying
                                status.state == RunState.Thinking -> Res.string.status_thinking
                                status.state == RunState.Acting -> Res.string.status_writing
                                else -> Res.string.status_done
                            },
                        ),
                        elapsed = elapsed(status.startedAtMillis),
                        stopLabel = stringResource(Res.string.status_stop),
                        onStop = { act(ChatAction.Stop) },
                        // A step or plan names what the user asked for; the default labels don't.
                        contentModifier = if (status.verb != null || status.plan != null) Modifier.userContent() else Modifier,
                        plan = status.plan?.let { steps ->
                            StatusPlanUi(
                                progress = stringResource(Res.string.plan_progress, steps.count { it.state == PlanRowState.Done }, steps.size),
                                steps = steps.map { it.toUi() },
                            )
                        },
                    )
                }
            }
            platform.AboveComposer()
            // Where the next task runs, right where it's typed; while a task runs, its status line stands here instead.
            AnimatedVisibility(!state.running, enter = WhittleTransitions.riseInPlace(), exit = WhittleTransitions.sinkOutPlace()) {
                ProjectChip(state.project, onClick = onChooseProject, modifier = Modifier.padding(start = WhittleSpace.xs))
            }
            AnimatedVisibility(state.screen != null, enter = WhittleTransitions.riseInPlace(), exit = WhittleTransitions.sinkOutPlace()) {
                ScreenChip(state.screen?.appName, onRemove = { act(ChatAction.RemoveScreen) }, modifier = Modifier.padding(start = WhittleSpace.xs).userContent())
            }
            AnimatedVisibility(
                state.attachments.isNotEmpty() || state.attaching > 0,
                enter = WhittleTransitions.riseInPlace(),
                exit = WhittleTransitions.sinkOutPlace(),
            ) {
                AttachmentTray(
                    attachments = state.attachments,
                    attaching = state.attaching,
                    onOpen = { openAttachment(state.attachments, it) },
                    onRemove = { act(ChatAction.RemoveAttachment(it.path)) },
                    modifier = Modifier.userContent(),
                    imagesUnseen = state.imagesUnseen,
                )
            }
            Composer(
                value = state.input,
                onValueChange = { act(ChatAction.UpdateInput(it)) },
                placeholder = stringResource(if (state.running) Res.string.composer_placeholder_running else Res.string.composer_placeholder),
                sendLabel = stringResource(Res.string.composer_send),
                onSend = { act(ChatAction.Send) },
                enabled = state.model != null,
                modifier = Modifier.userContent(),
                voice = voice,
                attach = onAttach?.let { ComposerAttach(stringResource(Res.string.composer_attach), it) },
                hasAttachments = state.attachments.isNotEmpty(),
                focusRequester = composerFocus,
            )
        }
    }
}

@Composable
private fun EmptyThread(state: ChatScreenState, act: (ChatAction) -> Unit, modifier: Modifier = Modifier) {
    // Centred while it fits; with the keyboard up or a long draft it scrolls instead of running under the dock.
    BoxWithConstraints(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = readingInset(maxWidth, WhittleSpace.xxl), vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.s, Alignment.CenterVertically),
        ) {
            WelcomeMascot(Modifier.padding(bottom = WhittleSpace.m))
            Text(stringResource(Res.string.chat_empty_headline), style = MaterialTheme.typography.headlineMedium)
            state.model?.let {
                Text(stringResource(Res.string.chat_empty_body, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.project == null) {
                CarryOn(state.projects.take(state.limits.recentProjects)) { act(ChatAction.ChooseProject(it)) }
                // Not in the very first chat, which keeps its welcome simple.
                if (state.chats.isNotEmpty()) AppIdeas(state.appIdeas) { act(ChatAction.UseIdea(it)) }
            }
            // Only before the first chat: three things to try that work right away.
            Starters(if (state.chats.isEmpty()) state.starters else emptyList()) { act(ChatAction.SendText(it)) }
        }
    }
}

/** A turn's final answer with its footer: copy (the whole turn's answers), and the sources it cites. */
@Composable
private fun Answer(item: ThreadRow.Narration, footer: TurnFooter, act: (ChatAction) -> Unit, modifier: Modifier) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(Res.string.answer_copy)
    val ui = AnswerFooterUi(
        copyLabel = copyLabel,
        copiedLabel = stringResource(Res.string.answer_copied),
        sourcesLabel = footer.sources.takeIf { it.isNotEmpty() }?.let { pluralStringResource(Res.plurals.sources_count, it.size, it.size) },
        sources = footer.sources.map { SourceUi(it.title, it.host, it.url) },
        sourcesExpanded = footer.sourcesExpanded,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        Narration(item.text, streaming = false)
        AnswerFooter(
            footer = ui,
            // Plain text: what the user reads, without the formatting marks.
            onCopy = {
                val plain = lightMarkdown(footer.copyText, SpanStyle()).text
                scope.launch { clipboard.setClipEntry(plainTextClip(copyLabel, plain)) }
            },
            onToggleSources = { footer.sourcesKey?.let { act(ChatAction.ToggleSources(it)) } },
            onOpenSource = { uriHandler.openUri(it.url) },
        )
    }
}

/**
 * The open chat's own actions: pin it to the top of the list, or delete it. [pinned] is null while the chat
 * hasn't been saved yet (nothing sent), so there is nothing to pin or delete.
 */
@Composable
private fun ChatMenu(pinned: Boolean?, onPin: (Boolean) -> Unit, onDelete: () -> Unit, shape: Shape) {
    var open by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val haptics = WhittleTheme.haptics
    // The menu anchors to this box, so it opens under the ⋮ button.
    Box {
        TopBarAction("more_vert", stringResource(Res.string.chat_more), onClick = { open = true }, shape = shape, modifier = TopBarGroupAction)
        WhittleMenu(
            expanded = open,
            onDismiss = { open = false },
            groups = listOf(
                listOf(MenuAction(stringResource(if (pinned == true) Res.string.chat_unpin else Res.string.chat_pin), "keep", { onPin(pinned != true) }, iconFilled = pinned == true, enabled = pinned != null)),
                listOf(MenuAction(stringResource(Res.string.chat_delete), "delete", { confirmingDelete = true }, destructive = true, enabled = pinned != null)),
            ),
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(Res.string.sidebar_delete_title)) },
            text = { Text(stringResource(Res.string.sidebar_delete_body)) },
            confirmButton = {
                TextButton(onClick = { haptics.perform(WhittleHaptic.Reject); confirmingDelete = false; onDelete() }) {
                    Text(stringResource(Res.string.sidebar_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(Res.string.sidebar_delete_cancel)) } },
        )
    }
}

/** What each attachment problem says, worded with the limits it hit. */
@Composable
private fun attachmentProblemMessages(limits: ChatLimits): Map<AttachmentProblem, String> = mapOf(
    AttachmentProblem.TooMany to pluralStringResource(Res.plurals.attach_too_many, limits.maxAttachments, limits.maxAttachments),
    AttachmentProblem.TooLarge to stringResource(Res.string.attach_too_large, sizeText(limits.maxAttachmentBytes)),
    AttachmentProblem.Unreadable to stringResource(Res.string.attach_unreadable),
    AttachmentProblem.NoWorkspace to stringResource(Res.string.attach_no_workspace),
)

/** Ticks while the status line is visible. */
@Composable
private fun elapsed(startedAtMillis: Long): String {
    var now by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            now = nowMillis()
            delay(TICK_MILLIS)
        }
    }
    return elapsedText(startedAtMillis, now)
}

private fun CardText.toUi(id: String) = DecisionUi(
    id = id,
    icon = icon,
    title = title,
    body = body,
    payloadLabel = payloadLabel,
    payload = payload,
    approveLabel = approve,
    declineLabel = decline,
    site = site,
)

@Composable
private fun StepRow.toUi(): StepUi {
    val openFile = LocalOpenFile.current
    return StepUi(
        id = id,
        icon = icon,
        label = label,
        detail = stepDetail(status),
        status = when (status) {
            StepRowStatus.Running -> StepStatus.Running
            StepRowStatus.Done -> StepStatus.Done
            StepRowStatus.Failed -> StepStatus.Failed
            StepRowStatus.Blocked, StepRowStatus.NeedsAccess -> StepStatus.Blocked
            StepRowStatus.Stopped -> StepStatus.Stopped
        },
        inspection = inspection,
        site = site,
        onOpen = file?.let { path -> openFile?.let { open -> { open(path) } } },
    )
}

private val RunState.agentState: AgentState
    get() = when (this) {
        RunState.Thinking -> AgentState.Thinking
        RunState.Acting -> AgentState.Acting
        RunState.Done -> AgentState.Done
    }

private fun PlanRow.toUi(): PlanStepUi = PlanStepUi(
    text = text,
    state = when (state) {
        PlanRowState.Done -> PlanStepState.Done
        PlanRowState.Active -> PlanStepState.Active
        PlanRowState.Pending -> PlanStepState.Pending
    },
)

private const val TICK_MILLIS = 250L

/** Files, new chat and the overflow menu share one connected group. */
private const val TOP_BAR_ACTIONS = 3
private const val FOOTER_KEY = "thread-footer"
