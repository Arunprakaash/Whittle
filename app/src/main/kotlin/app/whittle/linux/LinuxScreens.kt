package app.whittle.linux

import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.StepGroup
import app.whittle.design.components.StepStatus
import app.whittle.design.components.StepUi
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WelcomeMascot
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import kotlinx.coroutines.launch

/** Onboarding, right after the model: offers Linux once. Leaving it, set up or not, goes on to the chat. */
@Composable
fun LinuxSetupScreen(graph: AppGraph) {
    val state by graph.linux.state.collectAsStateWithLifecycle()
    val used by produceState(0L, state) { value = graph.linux.usedBytes() }
    val leave = { graph.appScope.launch { graph.settings.setLinuxOffered() }; Unit }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
        ) {
            item { WelcomeMascot() }
            linuxContent(graph, state, usedBytes = used, readyBody = R.string.linux_ready_body)
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    when (state) {
                        LinuxState.NotSetUp -> {
                            SetUpButton(graph)
                            TextButton(onClick = leave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.linux_skip)) }
                        }
                        is LinuxState.Failed -> {
                            SetUpButton(graph, R.string.linux_try_again)
                            TextButton(onClick = leave, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.linux_skip)) }
                        }
                        is LinuxState.SettingUp, is LinuxState.Ready -> Button(onClick = leave, modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.default)) {
                            Text(stringResource(R.string.linux_continue))
                        }
                    }
                }
            }
        }
    }
}

/** Settings › Linux workspace: set it up, or see where its space went and repair, reset or remove it. */
@Composable
fun LinuxWorkspaceScreen(graph: AppGraph, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val state by graph.linux.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf<Confirm?>(null) }
    var cleared by remember { mutableIntStateOf(0) }
    val used by produceState(0L, state, cleared) { value = graph.linux.usedBytes() }
    val usage by produceState<LinuxUsage?>(null, state, cleared) { value = graph.linux.usage() }
    val processes by graph.processes.processes.collectAsStateWithLifecycle()
    val live = processes.filter { it.live }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("arrow_back", stringResource(R.string.settings_back), onBack)
                Text(stringResource(R.string.linux_title), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
        ) {
            linuxContent(graph, state, usedBytes = used, readyBody = R.string.linux_ready_settings_body)
            when (state) {
                LinuxState.NotSetUp -> item { SetUpButton(graph) }
                is LinuxState.Failed -> item { SetUpButton(graph, R.string.linux_try_again) }
                is LinuxState.SettingUp -> Unit
                is LinuxState.Ready -> {
                    usage?.let { breakdown ->
                        item {
                            StorageCard(breakdown) { graph.appScope.launch { graph.linux.clearCaches(); cleared++ } }
                        }
                    }
                    if (live.isNotEmpty()) item {
                        RunningRow(live.map { it.name }) { graph.appScope.launch { graph.processes.stopAll() } }
                    }
                    item { ManageRows(graph, used) { confirming = it } }
                }
            }
        }
    }
    confirming?.let { confirm -> ConfirmDialog(confirm, graph, onDone = { confirming = null }) }
}

/** Repair, reset and remove, from least to most drastic. */
@Composable
private fun ManageRows(graph: AppGraph, used: Long, onConfirm: (Confirm) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        SettingsRow(
            icon = "build",
            title = stringResource(R.string.linux_repair),
            subtitle = stringResource(R.string.linux_repair_body),
            shape = segmentedShape(0, MANAGE_ROWS),
            onClick = { LinuxSetupService.repair(context) },
        )
        SettingsRow(
            icon = "restart_alt",
            title = stringResource(R.string.linux_reset),
            subtitle = stringResource(R.string.linux_reset_body),
            shape = segmentedShape(1, MANAGE_ROWS),
            onClick = { onConfirm(Confirm.Reset) },
        )
        SettingsRow(
            icon = "delete",
            title = stringResource(R.string.linux_remove),
            subtitle = stringResource(R.string.linux_remove_body, Formatter.formatShortFileSize(context, used)),
            shape = segmentedShape(2, MANAGE_ROWS),
            destructive = true,
            onClick = { onConfirm(Confirm.Remove) },
        )
    }
}

@Composable
private fun ConfirmDialog(confirm: Confirm, graph: AppGraph, onDone: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(confirm.title)) },
        text = { Text(stringResource(confirm.body)) },
        confirmButton = {
            TextButton(onClick = {
                onDone()
                when (confirm) {
                    Confirm.Reset -> LinuxSetupService.reset(context)
                    Confirm.Remove -> graph.appScope.launch { graph.linux.remove() }
                }
            }) { Text(stringResource(confirm.action), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.connector_cancel)) } },
    )
}

/** Where the space went, as one bar, with the caches clearable. */
@Composable
private fun StorageCard(usage: LinuxUsage, onClearCaches: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val parts = listOf(
        Triple(R.string.linux_storage_base, usage.base, colors.primary),
        Triple(R.string.linux_storage_added, usage.added, colors.tertiary),
        Triple(R.string.linux_storage_caches, usage.caches, colors.outlineVariant),
    )
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl)).padding(WhittleSpace.l),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Row(Modifier.fillMaxWidth().height(WhittleSpace.m).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
            parts.filter { it.second > 0 }.forEach { (_, bytes, color) ->
                Box(Modifier.weight(bytes.toFloat().coerceAtLeast(MIN_WEIGHT)).fillMaxHeight().background(color))
            }
        }
        parts.forEach { (label, bytes, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                Box(Modifier.size(WhittleSpace.m).background(color, RoundedCornerShape(WhittleRadius.xs)))
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(Formatter.formatShortFileSize(context, bytes), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        if (usage.caches > 0) {
            FilledTonalButton(onClick = onClearCaches) {
                WhittleIcon("block", size = WhittleSize.Icon.m)
                Text(stringResource(R.string.linux_clear_caches, Formatter.formatShortFileSize(context, usage.caches)), modifier = Modifier.padding(start = WhittleSpace.s))
            }
        }
    }
}

/** What the agent has running in Linux right now, with one way to stop it all. */
@Composable
private fun RunningRow(names: List<String>, onStopAll: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.l)).padding(start = WhittleSpace.l, end = WhittleSpace.s, top = WhittleSpace.s, bottom = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(Modifier.size(WhittleSpace.s).background(colors.primary, CircleShape))
        Column(Modifier.weight(1f)) {
            Text(pluralStringResource(R.plurals.linux_processes_running, names.size, names.size), style = MaterialTheme.typography.titleSmall, color = colors.onSecondaryContainer)
            Text(names.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer, maxLines = 1)
        }
        TextButton(onClick = onStopAll) { Text(stringResource(R.string.notify_stop_all)) }
    }
}

private enum class Confirm(val title: Int, val body: Int, val action: Int) {
    Reset(R.string.linux_reset_title, R.string.linux_reset_confirm_body, R.string.linux_reset),
    Remove(R.string.linux_remove_title, R.string.linux_remove_confirm_body, R.string.linux_remove),
}

/** What both screens share: what Linux is and, by state, its sizes, its setup steps or that it's ready ([readyBody]). */
private fun androidx.compose.foundation.lazy.LazyListScope.linuxContent(graph: AppGraph, state: LinuxState, usedBytes: Long, readyBody: Int) {
    val image = graph.linux.image
    item { IntroCard(image?.name.orEmpty(), state, usedBytes, readyBody) }
    when (state) {
        LinuxState.NotSetUp -> item { Facts(image) }
        is LinuxState.SettingUp, is LinuxState.Failed -> item { Progress(state, image?.name.orEmpty()) }
        is LinuxState.Ready -> Unit
    }
}

@Composable
private fun IntroCard(imageName: String, state: LinuxState, usedBytes: Long, readyBody: Int) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val status = when (state) {
        LinuxState.NotSetUp -> stringResource(R.string.linux_status_not_set_up, imageName)
        is LinuxState.SettingUp -> stringResource(R.string.linux_status_setting_up, imageName)
        is LinuxState.Ready -> stringResource(R.string.linux_status_ready, imageName, Formatter.formatShortFileSize(context, usedBytes))
        is LinuxState.Failed -> stringResource(R.string.linux_status_failed, imageName)
    }
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl)).padding(WhittleSpace.xl),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Row(
                Modifier.size(WhittleSize.Tile.xl).background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.l)),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) { WhittleIcon("terminal", tint = colors.onSecondaryContainer) }
            Column {
                Text(stringResource(if (state is LinuxState.Ready) R.string.linux_ready_title else R.string.linux_intro_title), style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Text(
            stringResource(if (state is LinuxState.Ready) readyBody else R.string.linux_intro_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (state !is LinuxState.Ready) {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                listOf("package_2" to R.string.linux_get_apt, "memory" to R.string.linux_get_compile, "terminal" to R.string.linux_get_run).forEach { (icon, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                        WhittleIcon(icon, size = WhittleSize.Icon.l, tint = colors.primary)
                        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun Facts(image: LinuxImage?) {
    val context = LocalContext.current
    val facts = listOf(
        R.string.linux_fact_download to Formatter.formatShortFileSize(context, image?.sizeBytes ?: 0L),
        R.string.linux_fact_start to Formatter.formatShortFileSize(context, image?.setupSizeBytes ?: 0L),
        R.string.linux_fact_free to Formatter.formatShortFileSize(context, freeBytes(context)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.l)) {
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
            facts.forEachIndexed { index, (label, value) ->
                SettingsRow(icon = FACT_ICONS[index], title = stringResource(label), subtitle = null, shape = segmentedShape(index, facts.size), trailing = {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m), modifier = Modifier.padding(horizontal = WhittleSpace.xs)) {
            WhittleIcon("wifi", size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.linux_wifi_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The steps so far, as in a chat: done ones ticked, the current one running or failed with its reason. */
@Composable
private fun Progress(state: LinuxState, imageName: String) {
    val context = LocalContext.current
    val current = when (state) {
        is LinuxState.SettingUp -> state.step
        is LinuxState.Failed -> state.step
        else -> return
    }
    val steps = LinuxSetupStep.entries.filter { it.ordinal <= current.ordinal }.map { step ->
        val now = step == current
        StepUi(
            id = step.name,
            icon = STEP_ICONS.getValue(step),
            label = LinuxSteps.label(context, step, done = !now, imageName),
            detail = when {
                !now -> null
                state is LinuxState.Failed -> state.reason
                state is LinuxState.SettingUp -> LinuxSteps.detail(context, state)
                else -> null
            },
            status = when {
                !now -> StepStatus.Done
                state is LinuxState.Failed -> StepStatus.Failed
                else -> StepStatus.Running
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
        StepGroup(steps)
        if (state is LinuxState.SettingUp) {
            if (state.totalBytes > 0) {
                LinearProgressIndicator(progress = { LinuxSteps.percent(state) / PERCENT }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.linux_keeps_going), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetUpButton(graph: AppGraph, label: Int = R.string.linux_set_up) {
    val context = LocalContext.current
    Button(
        onClick = { LinuxSetupService.setUp(context) },
        enabled = graph.linux.image != null,
        modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.default),
    ) {
        WhittleIcon("download", size = WhittleSize.Icon.l)
        Text(stringResource(label), modifier = Modifier.padding(start = WhittleSpace.s))
    }
}

private fun freeBytes(context: Context): Long = StatFs(context.filesDir.path).availableBytes

private const val MANAGE_ROWS = 3
/** Keeps a small part visible in the bar. */
private const val MIN_WEIGHT = 1f
private const val PERCENT = 100f
private val FACT_ICONS = listOf("download", "folder_zip", "folder")
private val STEP_ICONS = mapOf(
    LinuxSetupStep.Download to "download",
    LinuxSetupStep.Unpack to "folder_zip",
    LinuxSetupStep.Configure to "shield",
    LinuxSetupStep.BasePackages to "package_2",
)
