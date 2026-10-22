package app.whittle.chat

import app.whittle.settings.VoiceDefaults
import app.whittle.design.components.ComposerVoice
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.core.content.ContextCompat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.MenuAction
import app.whittle.observability.posthog.userContent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import app.whittle.design.components.WelcomeMascot
import app.whittle.design.components.AgentState
import app.whittle.design.components.StatusPlanUi
import app.whittle.agent.PlanItem
import app.whittle.design.components.PlanStepState
import app.whittle.design.components.PlanStepUi
import app.whittle.design.components.PlanChecklist
import app.whittle.agent.PlanStatus
import androidx.activity.compose.BackHandler
import app.whittle.design.tokens.WhittleSize
import androidx.compose.material3.AlertDialog
import app.whittle.design.components.StatusLine
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.SpanStyle
import android.content.ClipData
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.R
import app.whittle.design.components.Composer
import app.whittle.design.components.LatestPill
import app.whittle.design.components.WhittleTransitions
import app.whittle.design.tokens.WhittleMotion
import app.whittle.design.components.DecisionCard
import app.whittle.design.components.DecisionUi
import app.whittle.design.components.Narration
import app.whittle.design.components.QuestionCard
import app.whittle.design.components.QuestionUi
import app.whittle.design.components.ResolvedChip
import app.whittle.design.components.AnswerFooter
import app.whittle.design.components.AnswerFooterUi
import app.whittle.design.components.AnswerSources
import app.whittle.design.components.lightMarkdown
import app.whittle.design.components.RunSummaryChip
import app.whittle.design.components.SourceUi
import app.whittle.design.components.TopBarAction
import app.whittle.files.FilesOpening
import app.whittle.files.FilesPlace
import app.whittle.design.components.TopBarActionGroup
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.TopBarGroupAction
import app.whittle.design.components.connectedShape
import app.whittle.design.components.StepGroup
import app.whittle.design.components.StepStatus
import app.whittle.design.components.StepUi
import app.whittle.design.components.UserMessage
import app.whittle.design.components.WhittleIcon
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSpace
import app.whittle.tools.android.AndroidCapabilities
import app.whittle.tools.presentation.CapabilityPresentation
import app.whittle.tools.presentation.DecisionText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatState,
    viewModel: ChatViewModel,
    sidebarMaxChats: Int,
    voiceDefaults: VoiceDefaults,
    previewHeight: Float,
    onViewAllChats: () -> Unit,
    onOpenSettings: () -> Unit,
    avatarSeed: Long?,
    onOpenProjects: () -> Unit,
    /** Opens the file browser, at the workspace or at a file a step wrote. */
    onOpenFiles: (FilesOpening) -> Unit,
    /** Shown just above the composer, such as Linux finishing its setup. */
    aboveComposer: @Composable () -> Unit = {},
) {
    var previewing by remember { mutableStateOf<ThreadItem.Process?>(null) }
    previewing?.let { item ->
        // The card may have restarted its process since; the preview follows the card.
        val current = state.items.firstOrNull { it.key == item.key } as? ThreadItem.Process ?: item
        LivePreviewSheet(current, viewModel, previewHeight, onDismiss = { previewing = null })
    }
    val chat = state.currentChat?.value
    CompositionLocalProvider(
        LocalOpenPreview provides { item -> previewing = item },
        LocalOpenFile provides { path -> onOpenFiles(FilesOpening(FilesPlace.Document(path), chat)) },
    ) {
        ChatLayout(
            state, viewModel, sidebarMaxChats, voiceDefaults, onViewAllChats, onOpenSettings, avatarSeed, onOpenProjects, aboveComposer,
            onOpenWorkspace = { onOpenFiles(FilesOpening(FilesPlace.Folder(""), chat)) },
        )
    }
}

/** Opens a process card's live preview; provided by [ChatScreen] so any thread row can use it. */
private val LocalOpenPreview = staticCompositionLocalOf<(ThreadItem.Process) -> Unit> { {} }

/** Opens a file in the workspace by its path there, such as one a step wrote; provided by [ChatScreen]. */
private val LocalOpenFile = staticCompositionLocalOf<(String) -> Unit> { {} }

@Composable
private fun ChatLayout(
    state: ChatState,
    viewModel: ChatViewModel,
    sidebarMaxChats: Int,
    voiceDefaults: VoiceDefaults,
    onViewAllChats: () -> Unit,
    onOpenSettings: () -> Unit,
    avatarSeed: Long?,
    onOpenProjects: () -> Unit,
    aboveComposer: @Composable () -> Unit,
    onOpenWorkspace: () -> Unit,
) {
    val haptics = WhittleTheme.haptics
    LaunchedEffect(viewModel) { viewModel.haptics.collect { haptics.perform(it) } }
    val requestCapability = rememberCapabilityRequest(state.items, viewModel)
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
                maxChats = sidebarMaxChats,
                onNewChat = { closeThen(viewModel::newChat) },
                onOpen = { id -> closeThen { viewModel.openChat(id) } },
                onPin = viewModel::setPinned,
                onDelete = viewModel::deleteChat,
                onViewAll = { closeThen(onViewAllChats) },
                onOpenSettings = { closeThen(onOpenSettings) },
                avatarSeed = avatarSeed,
                onOpenProjects = { closeThen(onOpenProjects) },
            )
        },
    ) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    TopBarButton(
                        icon = "short_text",
                        contentDescription = stringResource(R.string.sidebar_open),
                        onClick = { scope.launch { drawer.open() } },
                        modifier = Modifier.padding(start = WhittleSpace.m),
                    )
                },
                title = {
                    Text(
                        title ?: stringResource(R.string.chat_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.userContent(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    TopBarActionGroup(Modifier.padding(end = WhittleSpace.m)) {
                        TopBarAction(
                            icon = "folder_open",
                            contentDescription = stringResource(R.string.files_open),
                            onClick = onOpenWorkspace,
                            shape = connectedShape(0, TOP_BAR_ACTIONS),
                            modifier = TopBarGroupAction,
                        )
                        TopBarAction(
                            icon = "edit_square",
                            contentDescription = stringResource(R.string.sidebar_new_chat),
                            onClick = viewModel::newChat,
                            shape = connectedShape(1, TOP_BAR_ACTIONS),
                            modifier = TopBarGroupAction,
                        )
                        val current = state.chats.firstOrNull { it.id == state.currentChat }
                        ChatMenu(
                            pinned = current?.pinned,
                            onPin = { pinned -> current?.let { viewModel.setPinned(it.id, pinned) } },
                            onDelete = { current?.let { viewModel.deleteChat(it.id) } },
                            shape = connectedShape(2, TOP_BAR_ACTIONS),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.restoring -> Unit
                    state.items.isEmpty() -> EmptyThread(state)
                    // One list per chat: switching replaces the thread instead of animating one chat into another.
                    else -> key(state.currentChat) { Thread(state.items, state.running, viewModel, requestCapability) }
                }
            }
            Dock(state, viewModel, voiceDefaults, aboveComposer)
        }
    }
    }
}

/**
 * Reads top to bottom like a document. While the user is at the bottom the view follows new content
 * as it streams; scrolling up stops following, and the Latest pill brings them back.
 */
@Composable
private fun Thread(items: List<ThreadItem>, working: Boolean, viewModel: ChatViewModel, requestCapability: (ThreadItem.Capability) -> Unit) {
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
    LaunchedEffect(items.size, items.lastOrNull()) {
        if (following && items.isNotEmpty()) listState.scrollToItem(items.lastIndex, scrollOffset = Int.MAX_VALUE)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            // One footer per turn, under its last answer; the turn's sources join it instead of a row of their own.
            // A plan lives in the status card while it runs; afterwards it's in the run's summary.
            val plan = AnswerFooters.plan(items)
            items(items.filterNot { it.key in plan.folded || it is ThreadItem.Plan }, key = { it.key }) { item ->
                ThreadRow(item, viewModel, requestCapability, Modifier.animateItem(), plan.footers[item.key])
            }
        }
        AnimatedVisibility(
            visible = !following && listState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = WhittleSpace.s),
            // A small surface: the fast spatial spring gives it Expressive's slight overshoot as it lands.
            enter = WhittleTransitions.riseIn(WhittleMotion.spatialFast),
            exit = WhittleTransitions.sinkOut(),
        ) {
            LatestPill(stringResource(R.string.thread_latest), working = working) {
                following = true
                scope.launch { listState.animateScrollToItem(items.lastIndex, scrollOffset = Int.MAX_VALUE) }
            }
        }
    }
}

@Composable
private fun ThreadRow(
    item: ThreadItem,
    viewModel: ChatViewModel,
    requestCapability: (ThreadItem.Capability) -> Unit,
    modifier: Modifier = Modifier,
    footer: TurnFooter? = null,
) {
    // What the user and the agent wrote, did and made stays out of session recordings; notices don't.
    val content = modifier.userContent()
    when (item) {
        is ThreadItem.User -> UserMessage(item.text, content, steerLabel = if (item.steer) stringResource(R.string.steer_label) else null)
        is ThreadItem.Narration -> if (footer != null) {
            Answer(item, footer, viewModel, content)
        } else {
            Narration(item.text, item.streaming, content)
        }
        is ThreadItem.Steps -> StepGroup(item.steps.map { it.toUi() }, content)
        is ThreadItem.Decision -> DecisionCard(
            item.text.toUi(item.key),
            onApprove = { viewModel.answer(item, approve = true) },
            onDecline = { viewModel.answer(item, approve = false) },
            modifier = content,
        )
        is ThreadItem.Question -> QuestionCard(
            QuestionUi(item.key, item.text.icon, item.text.question, item.text.options),
            onPick = { viewModel.answer(item, it) },
            modifier = content,
            answer = item.answer,
        )
        is ThreadItem.Capability -> DecisionCard(
            item.presentation.toUi(item.key),
            onApprove = { requestCapability(item) },
            onDecline = { viewModel.answer(item, granted = false) },
            modifier = modifier,
        )
        is ThreadItem.Interrupted -> Row(
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        ) {
            Text(
                stringResource(R.string.notice_interrupted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { viewModel.continueRun(item) }) { Text(stringResource(R.string.notice_continue)) }
        }
        is ThreadItem.Resolved -> ResolvedChip(item.outcome, item.declined, content)
        is ThreadItem.Summary -> Column(modifier, verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            RunSummaryChip(
                text = pluralStringResource(R.plurals.run_summary, item.stepCount, item.stepCount, item.seconds),
                expanded = item.expanded,
                onToggle = { viewModel.toggleSummary(item.key) },
            )
            AnimatedVisibility(item.expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                    item.folded.forEach { folded -> ThreadRow(folded, viewModel, requestCapability) }
                }
            }
        }
        is ThreadItem.Process -> ProcessCard(item, viewModel, onPreview = LocalOpenPreview.current, modifier = content)
        is ThreadItem.Plan -> PlanChecklist(
            title = stringResource(R.string.plan_title, item.steps.count { it.status == PlanStatus.COMPLETED }, item.steps.size),
            steps = item.steps.map { it.toUi() },
            modifier = content,
        )
        is ThreadItem.Sources -> {
            val uriHandler = LocalUriHandler.current
            AnswerSources(
                label = pluralStringResource(R.plurals.sources_count, item.sources.size, item.sources.size),
                sources = item.sources.map { SourceUi(it.title, it.host, it.url) },
                expanded = item.expanded,
                onToggle = { viewModel.toggleSources(item.key) },
                onOpen = { uriHandler.openUri(it.url) },
                modifier = content,
            )
        }
        is ThreadItem.Notice -> Text(
            noticeText(item),
            modifier = modifier,
            style = MaterialTheme.typography.bodyMedium,
            color = if (item.kind.isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Dock(state: ChatState, viewModel: ChatViewModel, voiceDefaults: VoiceDefaults, aboveComposer: @Composable () -> Unit) {
    val voice = rememberVoice(voiceDefaults, state.input, viewModel::updateInput)
    Column(
        Modifier.padding(start = WhittleSpace.m, end = WhittleSpace.m, bottom = WhittleSpace.s),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        AnimatedVisibility(state.status != null, enter = WhittleTransitions.riseInPlace(), exit = WhittleTransitions.sinkOutPlace()) {
            state.status?.let { status ->
                StatusLine(
                    state = status.state,
                    verb = status.verb ?: stringResource(
                        when {
                            status.phase == StatusPhase.Retrying -> R.string.status_retrying
                            status.state == AgentState.Thinking -> R.string.status_thinking
                            status.state == AgentState.Acting -> R.string.status_writing
                            else -> R.string.status_done
                        },
                    ),
                    elapsed = elapsed(status.startedAtMillis),
                    stopLabel = stringResource(R.string.status_stop),
                    onStop = viewModel::stop,
                    // A step or plan names what the user asked for; the default labels don't.
                    contentModifier = if (status.verb != null || status.plan != null) Modifier.userContent() else Modifier,
                    plan = status.plan?.let { steps ->
                        StatusPlanUi(
                            progress = stringResource(R.string.plan_progress, steps.count { it.status == PlanStatus.COMPLETED }, steps.size),
                            steps = steps.map { it.toUi() },
                        )
                    },
                )
            }
        }
        aboveComposer()
        Composer(
            value = state.input,
            onValueChange = viewModel::updateInput,
            placeholder = stringResource(if (state.running) R.string.composer_placeholder_running else R.string.composer_placeholder),
            sendLabel = stringResource(R.string.composer_send),
            onSend = viewModel::send,
            enabled = state.model != null,
            modifier = Modifier.userContent(),
            voice = voice,
        )
    }
}

/**
 * The composer's mic: tap to speak, and the words land in the input as they're heard, after anything already
 * typed. Null when the phone has no speech recognizer.
 */
@Composable
private fun rememberVoice(defaults: VoiceDefaults, input: String, onInput: (String) -> Unit): ComposerVoice? {
    val context = LocalContext.current
    val voice = remember(context, defaults) { VoiceInput(context, defaults) }
    if (!remember(voice) { voice.available }) return null
    DisposableEffect(voice) { onDispose { voice.cancel() } }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    val currentInput by rememberUpdatedState(input)
    val currentOnInput by rememberUpdatedState(onInput)
    val errors = mapOf(
        VoiceError.NoPermission to stringResource(R.string.voice_no_permission),
        VoiceError.Unavailable to stringResource(R.string.voice_unavailable),
        VoiceError.Network to stringResource(R.string.voice_network),
        VoiceError.Other to stringResource(R.string.voice_failed),
    )
    val quiet = {
        voice.cancel()
        listening = false
        level = 0f
    }
    // Listening belongs to the screen in front of the user; leaving the app ends it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (listening) quiet() }
    val start = {
        // What was typed before speaking stays; each partial result replaces only what's been heard so far.
        val base = currentInput.trimEnd().let { if (it.isEmpty()) it else "$it " }
        listening = true
        voice.start(object : VoiceListener {
            override fun onPartial(text: String) = currentOnInput(base + text)

            override fun onFinal(text: String) = currentOnInput(base + text)

            override fun onLevel(heard: Float) {
                level = heard
            }

            override fun onEnd(error: VoiceError?) {
                quiet()
                error?.let { Toast.makeText(context, errors.getValue(it), Toast.LENGTH_LONG).show() }
            }
        })
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else Toast.makeText(context, errors.getValue(VoiceError.NoPermission), Toast.LENGTH_LONG).show()
    }
    return ComposerVoice(
        listening = listening,
        level = level,
        listeningPlaceholder = stringResource(R.string.composer_listening),
        startLabel = stringResource(R.string.composer_speak),
        stopLabel = stringResource(R.string.composer_stop_listening),
        onToggle = {
            when {
                listening -> voice.stop()
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> start()
                else -> permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onTyped = { if (listening) quiet() },
    )
}

@Composable
private fun EmptyThread(state: ChatState) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = WhittleSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.s, Alignment.CenterVertically),
    ) {
        WelcomeMascot(Modifier.padding(bottom = WhittleSpace.m))
        Text(stringResource(R.string.chat_empty_headline), style = MaterialTheme.typography.headlineMedium)
        state.model?.let {
            Text(stringResource(R.string.chat_empty_body, it.displayName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A turn's final answer with its footer: copy (the whole turn's answers), and the sources it cites. */
@Composable
private fun Answer(item: ThreadItem.Narration, footer: TurnFooter, viewModel: ChatViewModel, modifier: Modifier) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.answer_copy)
    val ui = AnswerFooterUi(
        copyLabel = copyLabel,
        copiedLabel = stringResource(R.string.answer_copied),
        sourcesLabel = footer.sources.takeIf { it.isNotEmpty() }?.let { pluralStringResource(R.plurals.sources_count, it.size, it.size) },
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
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, plain))) }
            },
            onToggleSources = { footer.sourcesKey?.let(viewModel::toggleSources) },
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
        TopBarAction("more_vert", stringResource(R.string.chat_more), onClick = { open = true }, shape = shape, modifier = TopBarGroupAction)
        WhittleMenu(
            expanded = open,
            onDismiss = { open = false },
            groups = listOf(
                listOf(MenuAction(stringResource(if (pinned == true) R.string.chat_unpin else R.string.chat_pin), "keep", { onPin(pinned != true) }, iconFilled = pinned == true, enabled = pinned != null)),
                listOf(MenuAction(stringResource(R.string.chat_delete), "delete", { confirmingDelete = true }, destructive = true, enabled = pinned != null)),
            ),
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.sidebar_delete_title)) },
            text = { Text(stringResource(R.string.sidebar_delete_body)) },
            confirmButton = {
                TextButton(onClick = { haptics.perform(WhittleHaptic.Reject); confirmingDelete = false; onDelete() }) {
                    Text(stringResource(R.string.sidebar_delete_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.sidebar_delete_cancel)) } },
        )
    }
}

/**
 * Allow on a capability card shows Android's own permission prompt, then reports the result. If access
 * was already granted (say, in system settings), it reports straight away.
 */
@Composable
private fun rememberCapabilityRequest(items: List<ThreadItem>, viewModel: ChatViewModel): (ThreadItem.Capability) -> Unit {
    val context = LocalContext.current
    var requestingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        items.firstOrNull { it.key == requestingKey }?.let { viewModel.answer(it as ThreadItem.Capability, granted) }
        requestingKey = null
    }
    return { item ->
        val permission = AndroidCapabilities.permissionFor(item.capability)
        if (permission == null || AndroidCapabilities.isGranted(context, item.capability)) {
            viewModel.answer(item, granted = true)
        } else {
            requestingKey = item.key
            launcher.launch(permission)
        }
    }
}

/** Ticks while the status line is visible. */
@Composable
private fun elapsed(startedAtMillis: Long): String {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(TICK_MILLIS)
        }
    }
    val seconds = ((now - startedAtMillis) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return stringResource(R.string.elapsed_time, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

private fun DecisionText.toUi(id: String) = DecisionUi(
    id = id,
    icon = icon,
    title = title,
    body = body,
    payloadLabel = payloadLabel,
    payload = payload,
    approveLabel = approve,
    approveIcon = approveIcon,
    declineLabel = decline,
    site = site,
)

private fun CapabilityPresentation.toUi(id: String) = DecisionUi(
    id = id,
    icon = icon,
    title = title,
    body = body,
    payloadLabel = null,
    payload = null,
    approveLabel = approve,
    approveIcon = CAPABILITY_APPROVE_ICON,
    declineLabel = decline,
)

@Composable
private fun StepModel.toUi(): StepUi {
    val openFile = LocalOpenFile.current
    return toUi(onOpen = file?.let { path -> { openFile(path) } })
}

@Composable
private fun StepModel.toUi(onOpen: (() -> Unit)?): StepUi = StepUi(
    id = id,
    icon = icon,
    label = label,
    detail = when (status) {
        StepState.Blocked -> stringResource(R.string.step_blocked)
        StepState.Failed -> stringResource(R.string.step_failed)
        StepState.Stopped -> stringResource(R.string.step_stopped)
        StepState.NeedsAccess -> stringResource(R.string.step_needs_access)
        StepState.Running, StepState.Done -> null
    },
    status = when (status) {
        StepState.Running -> StepStatus.Running
        StepState.Done -> StepStatus.Done
        StepState.Failed -> StepStatus.Failed
        StepState.Blocked, StepState.NeedsAccess -> StepStatus.Blocked
        StepState.Stopped -> StepStatus.Stopped
    },
    inspection = inspection,
    site = site,
    onOpen = onOpen,
)

@Composable
private fun noticeText(notice: ThreadItem.Notice): String = when (notice.kind) {
    NoticeKind.Stopped -> stringResource(R.string.notice_stopped)
    NoticeKind.PausedBudget -> stringResource(R.string.notice_paused_budget)
    NoticeKind.PausedSteps -> stringResource(R.string.notice_paused_steps)
    NoticeKind.FailedKey -> stringResource(R.string.notice_failed_key)
    NoticeKind.FailedNetwork -> stringResource(R.string.notice_failed_network)
    NoticeKind.FailedProvider -> stringResource(R.string.notice_failed_provider, notice.statusCode ?: 0)
    NoticeKind.FailedGeneric -> stringResource(R.string.notice_failed_generic)
}

/** Permission cards always confirm with a check; their words come from config. */
private const val CAPABILITY_APPROVE_ICON = "check"
private const val TICK_MILLIS = 250L
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

/** Files, new chat and the overflow menu share one connected group. */
private const val TOP_BAR_ACTIONS = 3

private fun PlanItem.toUi(): PlanStepUi = PlanStepUi(
    text = content,
    state = when (status) {
        PlanStatus.COMPLETED -> PlanStepState.Done
        PlanStatus.IN_PROGRESS -> PlanStepState.Active
        PlanStatus.PENDING -> PlanStepState.Pending
    },
)
