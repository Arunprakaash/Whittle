package app.whittle.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.whittle.R
import app.whittle.design.components.Composer
import app.whittle.design.components.LatestPill
import app.whittle.design.components.DecisionCard
import app.whittle.design.components.Narration
import app.whittle.design.components.ResolvedChip
import app.whittle.design.components.RunSummaryChip
import app.whittle.design.components.StatusLine
import app.whittle.design.components.StepGroup
import app.whittle.design.components.StepStatus
import app.whittle.design.components.StepUi
import app.whittle.design.components.UserMessage
import app.whittle.design.components.WhittleIcon
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatState, viewModel: ChatViewModel) {
    val haptics = WhittleTheme.haptics
    LaunchedEffect(viewModel) { viewModel.haptics.collect { haptics.perform(it) } }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.titleMedium) },
                actions = { ChatMenu(onNewTask = viewModel::newTask, onChangeModel = viewModel::changeModel) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.items.isEmpty()) EmptyThread(state) else Thread(state.items, viewModel)
            }
            Dock(state, viewModel)
        }
    }
}

/**
 * Reads top to bottom like a document. While the user is at the bottom the view follows new content
 * as it streams; scrolling up stops following, and the Latest pill brings them back.
 */
@Composable
private fun Thread(items: List<ThreadItem>, viewModel: ChatViewModel) {
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
            items(items, key = { it.key }) { item -> ThreadRow(item, viewModel, Modifier.animateItem()) }
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
private fun ThreadRow(item: ThreadItem, viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    when (item) {
        is ThreadItem.User -> UserMessage(item.text, modifier, steerLabel = if (item.steer) stringResource(R.string.steer_label) else null)
        is ThreadItem.Narration -> Narration(item.text, item.streaming, modifier)
        is ThreadItem.Steps -> StepGroup(item.steps.map { it.toUi() }, modifier)
        is ThreadItem.Decision -> DecisionCard(item.ui, onApprove = { viewModel.answer(item, approve = true) }, onDecline = { viewModel.answer(item, approve = false) }, modifier = modifier)
        is ThreadItem.Resolved -> ResolvedChip(item.outcome, item.declined, modifier)
        is ThreadItem.Summary -> Column(modifier, verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            RunSummaryChip(
                text = pluralStringResource(R.plurals.run_summary, item.stepCount, item.stepCount, item.seconds),
                expanded = item.expanded,
                onToggle = { viewModel.toggleSummary(item.key) },
            )
            AnimatedVisibility(item.expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                    item.folded.forEach { folded -> ThreadRow(folded, viewModel) }
                }
            }
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

@Composable
private fun ChatMenu(onNewTask: () -> Unit, onChangeModel: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { WhittleIcon("more_vert", contentDescription = stringResource(R.string.chat_more)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text(stringResource(R.string.chat_new_task)) }, onClick = { open = false; onNewTask() })
        DropdownMenuItem(text = { Text(stringResource(R.string.chat_change_model)) }, onClick = { open = false; onChangeModel() })
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

@Composable
private fun StepModel.toUi(): StepUi = StepUi(
    id = id,
    icon = icon,
    label = label,
    detail = when (status) {
        StepState.Blocked -> stringResource(R.string.step_blocked)
        StepState.Failed -> stringResource(R.string.step_failed)
        StepState.Stopped -> stringResource(R.string.step_stopped)
        StepState.Running, StepState.Done -> null
    },
    status = when (status) {
        StepState.Running -> StepStatus.Running
        StepState.Done -> StepStatus.Done
        StepState.Failed -> StepStatus.Failed
        StepState.Blocked -> StepStatus.Blocked
        StepState.Stopped -> StepStatus.Stopped
    },
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

private const val TICK_MILLIS = 250L
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
