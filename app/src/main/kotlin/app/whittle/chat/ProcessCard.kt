package app.whittle.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.agent.PastedKind
import app.whittle.R
import app.whittle.design.components.MenuAction
import app.whittle.design.components.WhittleIcon
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.tools.workspace.ProcessInfo
import app.whittle.tools.workspace.ProcessStatus
import kotlinx.coroutines.delay

/**
 * A process the agent started, live in the chat: what runs and where, its latest output, and the likely next step
 * first (Preview while it serves, Ask to fix once it fails, Restart once it's gone).
 */
@Composable
fun ProcessCard(item: ThreadItem.Process, viewModel: ChatViewModel, onPreview: (ThreadItem.Process) -> Unit, modifier: Modifier = Modifier) {
    val processes by viewModel.processes.collectAsStateWithLifecycle()
    val info = processes.firstOrNull { it.id == item.processId }
    // Uptime and output tick while it runs; once it ends they settle.
    val now by produceState(System.currentTimeMillis(), info?.live) {
        value = System.currentTimeMillis()
        while (info?.live == true) {
            delay(TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    val askReady by viewModel.askToFixReady.collectAsStateWithLifecycle()
    val fixRequest = stringResource(R.string.process_fix_message, viewModel.fixName(item.name))
    var showingOutput by remember { mutableStateOf(false) }
    val look = look(info, now)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl)).padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Box(
                Modifier.size(WhittleSize.Tile.m).background(if (look.failed) colors.errorContainer else colors.secondaryContainer, RoundedCornerShape(WhittleRadius.m)),
                contentAlignment = Alignment.Center,
            ) { WhittleIcon(if (look.failed) "error" else "terminal", size = WhittleSize.Icon.l, tint = if (look.failed) colors.onErrorContainer else colors.onSecondaryContainer) }
            // One status line: what it's doing and for how long. The output is one tap away, under Output.
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    if (look.live || look.starting) LiveDot(colors.primary, pulsing = look.live)
                    Text(
                        look.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (look.failed) colors.error else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (look.failed && info != null) {
                CardMenu(stringResource(R.string.process_more), listOf(MenuAction(stringResource(R.string.process_restart), "restart_alt", { viewModel.restartProcess(item) })))
            }
        }
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            when {
                info == null -> Action("restart_alt", R.string.process_restart, primary = true) { viewModel.restartProcess(item) }
                info.live && info.listening -> {
                    Action("preview", R.string.process_preview, primary = true) { onPreview(item) }
                    Action("notes", R.string.process_output) { showingOutput = true }
                    StopAction { viewModel.stopProcess(item) }
                }
                info.live -> {
                    Action("notes", R.string.process_output) { showingOutput = true }
                    StopAction { viewModel.stopProcess(item) }
                }
                // Restart is in the ⋯ menu: fixing it comes first.
                look.failed -> {
                    Action("build", R.string.process_ask_fix, primary = true, enabled = askReady) {
                        viewModel.askToFix(fixRequest, PastedKind.PROCESS_OUTPUT, item.name, viewModel.processOutput(item, FIX_LINES))
                    }
                    Action("notes", R.string.process_output) { showingOutput = true }
                }
                else -> {
                    Action("restart_alt", R.string.process_restart, primary = info.status == ProcessStatus.Exited) { viewModel.restartProcess(item) }
                    Action("notes", R.string.process_output) { showingOutput = true }
                }
            }
        }
    }
    if (showingOutput) OutputSheet(item, viewModel, onDismiss = { showingOutput = false })
}

/** How the card reads for where the process is: its one status line, and whether it's live, starting or failed. */
private class Look(val status: String, val live: Boolean = false, val starting: Boolean = false, val failed: Boolean = false)

@Composable
private fun look(info: ProcessInfo?, now: Long): Look {
    if (info == null) return Look(stringResource(R.string.process_not_running))
    val ran = elapsed(info.startedAtMillis, info.endedAtMillis ?: now)
    return when {
        info.live && info.listening -> Look(stringResource(R.string.process_pill_live, info.port ?: 0, ran), live = true)
        info.live && info.status == ProcessStatus.Starting || info.live && info.port != null -> Look(
            info.port?.let { stringResource(R.string.process_waiting, it) } ?: stringResource(R.string.process_waiting_no_port),
            starting = true,
        )
        info.live -> Look(stringResource(R.string.process_running_for, ran), live = true)
        info.status == ProcessStatus.Exited && info.exitCode == KILLED -> Look(stringResource(R.string.process_pill_closed))
        info.status == ProcessStatus.Exited && info.exitCode != 0 -> Look(stringResource(R.string.process_pill_exited, info.exitCode ?: 0, ran), failed = true)
        else -> Look(stringResource(R.string.process_ran_for, ran))
    }
}

@Composable
private fun LiveDot(color: Color, pulsing: Boolean) {
    val alpha = if (pulsing) {
        rememberInfiniteTransition(label = "live").animateFloat(DIM, 1f, infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse), label = "alpha").value
    } else {
        DIM
    }
    Box(Modifier.size(WhittleSpace.s).alpha(alpha).background(color, CircleShape))
}

/** Stop, as a quiet text button: it's there when needed, without pulling the eye from Preview. */
@Composable
private fun StopAction(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.height(WhittleSize.Button.small)) { Text(stringResource(R.string.process_stop)) }
}

@Composable
private fun Action(icon: String, label: Int, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        WhittleIcon(icon, size = WhittleSize.Icon.m)
        Text(stringResource(label), modifier = Modifier.padding(start = WhittleSpace.s))
    }
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.height(WhittleSize.Button.small)) { content() }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.height(WhittleSize.Button.small)) { content() }
    }
}

/** The whole kept output, selectable, following new lines while it's open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OutputSheet(item: ThreadItem.Process, viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val output by produceState(viewModel.processOutput(item, SHEET_LINES), item.processId) {
        while (true) {
            value = viewModel.processOutput(item, SHEET_LINES)
            delay(TICK_MILLIS)
        }
    }
    val scroll = rememberScrollState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = WhittleSpace.xxl), verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Text(item.name, style = MaterialTheme.typography.headlineSmall)
            Text("$ ${item.command}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(
                    output.ifBlank { stringResource(R.string.process_no_output) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(WhittleRadius.l))
                        .padding(WhittleSpace.m)
                        .verticalScroll(scroll)
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun elapsed(from: Long, to: Long): String {
    val seconds = ((to - from) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return stringResource(R.string.elapsed_time, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

private const val TICK_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val FIX_LINES = 40
private const val SHEET_LINES = 2_000
private const val PULSE_MILLIS = 900
private const val DIM = 0.35f
/** Exit status of a process ended with SIGKILL from outside: on a phone, almost always Android reclaiming memory. */
private const val KILLED = 137
