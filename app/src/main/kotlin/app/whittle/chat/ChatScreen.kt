package app.whittle.chat

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import app.whittle.design.components.ArtifactCard
import app.whittle.artifacts.Artifacts
import app.whittle.artifacts.ArtifactSheet
import app.whittle.artifacts.ArtifactRef
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import app.whittle.design.components.TopBarActionGroup
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.TopBarGroupAction
import app.whittle.design.components.connectedShape
import app.whittle.design.components.StatusLine
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
    artifacts: Artifacts,
    artifactSheetHeight: Float,
    onViewAllChats: () -> Unit,
    onOpenArtifactFullScreen: (ArtifactRef) -> Unit,
) {
    var sheetArtifact by remember { mutableStateOf<ArtifactRef?>(null) }
    sheetArtifact?.let { ref ->
        ArtifactSheet(
            ref = ref,
            artifacts = artifacts,
            revision = state.workspaceRevision,
            heightFraction = artifactSheetHeight,
            onDismiss = { sheetArtifact = null },
            onFullScreen = { sheetArtifact = null; onOpenArtifactFullScreen(ref) },
        )
    }
    CompositionLocalProvider(LocalOpenArtifact provides { ref -> sheetArtifact = ref }) {
        ChatLayout(state, viewModel, sidebarMaxChats, onViewAllChats)
    }
}

/** Opens an artifact card's app in the sheet; provided by [ChatScreen] so any thread row can use it. */
private val LocalOpenArtifact = staticCompositionLocalOf<(ArtifactRef) -> Unit> { {} }

@Composable
private fun ChatLayout(state: ChatState, viewModel: ChatViewModel, sidebarMaxChats: Int, onViewAllChats: () -> Unit) {
    val haptics = WhittleTheme.haptics
    LaunchedEffect(viewModel) { viewModel.haptics.collect { haptics.perform(it) } }
    val requestCapability = rememberCapabilityRequest(state.items, viewModel)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    TopBarActionGroup(Modifier.padding(end = WhittleSpace.m)) {
                        TopBarAction(
                            icon = "edit_square",
                            contentDescription = stringResource(R.string.sidebar_new_chat),
                            onClick = viewModel::newChat,
                            shape = connectedShape(0, TOP_BAR_ACTIONS),
                            modifier = TopBarGroupAction,
                        )
                        ChatMenu(onChangeModel = viewModel::changeModel, shape = connectedShape(1, TOP_BAR_ACTIONS))
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
                    else -> key(state.currentChat) { Thread(state.items, viewModel, requestCapability) }
                }
            }
            Dock(state, viewModel)
        }
    }
    }
}

/**
 * Reads top to bottom like a document. While the user is at the bottom the view follows new content
 * as it streams; scrolling up stops following, and the Latest pill brings them back.
 */
@Composable
private fun Thread(items: List<ThreadItem>, viewModel: ChatViewModel, requestCapability: (ThreadItem.Capability) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) following = false }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canScrollForward -> if (!canScrollForward) following = true }
    }
    // Changes whenever something new appears or streaming text grows.
    val contentVersion = items.size to (items.lastOrNull() as? ThreadItem.Narration)?.text?.length
    LaunchedEffect(contentVersion) {
        if (following && items.isNotEmpty()) listState.scrollToItem(items.lastIndex, scrollOffset = Int.MAX_VALUE)
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Sources right after an answer join its footer row instead of taking a row of their own.
            val footerSources = answerSources(items)
            val attached = footerSources.values.mapTo(mutableSetOf()) { it.key }
            items(items.filterNot { it.key in attached }, key = { it.key }) { item ->
                ThreadRow(item, viewModel, requestCapability, Modifier.animateItem(), footerSources[item.key])
            }
        }
        AnimatedVisibility(
            visible = !following && listState.canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = WhittleSpace.s),
        ) {
            LatestPill(stringResource(R.string.thread_latest)) {
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
    sources: ThreadItem.Sources? = null,
) {
    when (item) {
        is ThreadItem.User -> UserMessage(item.text, modifier, steerLabel = if (item.steer) stringResource(R.string.steer_label) else null)
        is ThreadItem.Narration -> if (item.answer && !item.streaming) {
            Answer(item, sources, viewModel, modifier)
        } else {
            Narration(item.text, item.streaming, modifier)
        }
        is ThreadItem.Steps -> StepGroup(item.steps.map { it.toUi() }, modifier)
        is ThreadItem.Decision -> DecisionCard(
            item.text.toUi(item.key),
            onApprove = { viewModel.answer(item, approve = true) },
            onDecline = { viewModel.answer(item, approve = false) },
            modifier = modifier,
        )
        is ThreadItem.Question -> QuestionCard(
            QuestionUi(item.key, item.text.icon, item.text.question, item.text.options),
            onPick = { viewModel.answer(item, it) },
            modifier = modifier,
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
        is ThreadItem.Resolved -> ResolvedChip(item.outcome, item.declined, modifier)
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
        is ThreadItem.Artifact -> {
            val open = LocalOpenArtifact.current
            ArtifactCard(
                title = item.ref.title,
                kind = stringResource(R.string.artifact_kind),
                openLabel = stringResource(R.string.artifact_open),
                onOpen = { open(item.ref) },
                modifier = modifier,
            )
        }
        is ThreadItem.Sources -> {
            val uriHandler = LocalUriHandler.current
            AnswerSources(
                label = pluralStringResource(R.plurals.sources_count, item.sources.size, item.sources.size),
                sources = item.sources.map { SourceUi(it.title, it.host, it.url) },
                expanded = item.expanded,
                onToggle = { viewModel.toggleSources(item.key) },
                onOpen = { uriHandler.openUri(it.url) },
                modifier = modifier,
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
private fun Dock(state: ChatState, viewModel: ChatViewModel) {
    Column(
        Modifier.padding(start = WhittleSpace.m, end = WhittleSpace.m, bottom = WhittleSpace.s),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        AnimatedVisibility(state.status != null) {
            state.status?.let { status ->
                StatusLine(
                    state = status.state,
                    verb = status.verb ?: stringResource(R.string.status_thinking),
                    elapsed = elapsed(status.startedAtMillis),
                    stopLabel = stringResource(R.string.status_stop),
                    onStop = viewModel::stop,
                )
            }
        }
        Composer(
            value = state.input,
            onValueChange = viewModel::updateInput,
            placeholder = stringResource(if (state.running) R.string.composer_placeholder_running else R.string.composer_placeholder),
            sendLabel = stringResource(R.string.composer_send),
            onSend = viewModel::send,
            enabled = state.model != null,
        )
    }
}

@Composable
private fun EmptyThread(state: ChatState) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = WhittleSpace.xxl),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.s, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.chat_empty_headline), style = MaterialTheme.typography.headlineMedium)
        state.model?.let {
            Text(stringResource(R.string.chat_empty_body, it.displayName), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** An answer with its footer: copy, and the sources it cites. */
@Composable
private fun Answer(item: ThreadItem.Narration, sources: ThreadItem.Sources?, viewModel: ChatViewModel, modifier: Modifier) {
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.answer_copy)
    val footer = AnswerFooterUi(
        copyLabel = copyLabel,
        copiedLabel = stringResource(R.string.answer_copied),
        sourcesLabel = sources?.let { pluralStringResource(R.plurals.sources_count, it.sources.size, it.sources.size) },
        sources = sources?.sources.orEmpty().map { SourceUi(it.title, it.host, it.url) },
        sourcesExpanded = sources?.expanded == true,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        Narration(item.text, streaming = false)
        AnswerFooter(
            footer = footer,
            // Plain text: what the user reads, without the formatting marks.
            onCopy = {
                val plain = lightMarkdown(item.text, SpanStyle()).text
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(copyLabel, plain))) }
            },
            onToggleSources = { sources?.let { viewModel.toggleSources(it.key) } },
            onOpenSource = { uriHandler.openUri(it.url) },
        )
    }
}

/** Each answer's sources item, when it sits right after the answer. */
private fun answerSources(items: List<ThreadItem>): Map<String, ThreadItem.Sources> = buildMap {
    items.zipWithNext().forEach { (item, next) ->
        if (item is ThreadItem.Narration && item.answer && next is ThreadItem.Sources) put(item.key, next)
    }
}

@Composable
private fun ChatMenu(onChangeModel: () -> Unit, shape: Shape) {
    var open by remember { mutableStateOf(false) }
    // The menu anchors to this box, so it opens under the ⋮ button.
    Box {
        TopBarAction("more_vert", stringResource(R.string.chat_more), onClick = { open = true }, shape = shape, modifier = TopBarGroupAction)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.chat_change_model)) }, onClick = { open = false; onChangeModel() })
        }
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
private fun StepModel.toUi(): StepUi = StepUi(
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

private val NoticeKind.isFailure: Boolean
    get() = this == NoticeKind.FailedKey || this == NoticeKind.FailedNetwork || this == NoticeKind.FailedProvider || this == NoticeKind.FailedGeneric

/** Permission cards always confirm with a check; their words come from config. */
private const val CAPABILITY_APPROVE_ICON = "check"
private const val TICK_MILLIS = 250L
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L

/** New chat and the overflow menu share one connected group. */
private const val TOP_BAR_ACTIONS = 2
