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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.whittle.design.tokens.WhittleFonts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.apps.ToolchainFailure
import app.whittle.apps.ToolchainState
import app.whittle.apps.canRemoveToolchain
import app.whittle.onboarding.ONBOARDING_STEPS
import app.whittle.onboarding.OnboardingSteps
import kotlinx.coroutines.launch

/**
 * First run, before the model: offers Linux once. Set up starts it in the background and moves on, so it installs
 * while the user connects a model; Not now moves on without it. [step] is first run's step bar; null after an update.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinuxSetupScreen(graph: AppGraph, step: Int?) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val state by graph.linux.state.collectAsStateWithLifecycle()
    val leave = { graph.appScope.launch { graph.settings.setLinuxOffered() }; Unit }
    // Already set up (from Settings, say): there's nothing to offer.
    if (state is LinuxState.Ready) LaunchedEffect(Unit) { graph.settings.setLinuxOffered() }
    Column(
        Modifier.fillMaxSize().background(colors.surface).safeDrawingPadding().padding(horizontal = WhittleSpace.xxl),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            step?.let { current -> OnboardingSteps(current, ONBOARDING_STEPS, Modifier.padding(top = WhittleSpace.l, bottom = WhittleSpace.xxl)) }
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl)) {
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                    Text(stringResource(R.string.linux_onboarding_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.linux_onboarding_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                Column(
                    Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl)).padding(WhittleSpace.l),
                    verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
                ) {
                    GETS.forEach { (icon, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                            WhittleIcon(icon, size = WhittleSize.Icon.l, tint = colors.primary)
                            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                (state as? LinuxState.Failed)?.let { failed ->
                    Text(stringResource(R.string.linux_failed_reason, failed.reason), style = MaterialTheme.typography.bodyMedium, color = colors.error)
                }
                SizeFacts(graph.linux.image)
                Row(Modifier.padding(horizontal = WhittleSpace.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    WhittleIcon("wifi", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant)
                    Text(stringResource(R.string.linux_wifi_tip), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(top = WhittleSpace.l, bottom = WhittleSpace.xl), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
            if (state is LinuxState.SettingUp) {
                Button(onClick = leave, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.large)) {
                    Text(stringResource(R.string.linux_continue), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = { LinuxSetupService.setUp(context); leave() },
                    shapes = ButtonDefaults.shapes(),
                    enabled = graph.linux.image != null,
                    modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.large),
                ) {
                    WhittleIcon("download", size = WhittleSize.Icon.l)
                    Spacer(Modifier.width(WhittleSpace.s))
                    Text(
                        stringResource(if (state is LinuxState.Failed) R.string.linux_try_again else R.string.linux_onboarding_set_up),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                TextButton(onClick = leave, modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.default)) {
                    Text(stringResource(R.string.linux_skip), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** The download, the space setup takes and the space free, as plain rows with the numbers in mono. */
@Composable
private fun SizeFacts(image: LinuxImage?) {
    val context = LocalContext.current
    val facts = listOf(
        R.string.linux_fact_download to Formatter.formatShortFileSize(context, image?.sizeBytes ?: 0L),
        R.string.linux_fact_start to Formatter.formatShortFileSize(context, image?.setupSizeBytes ?: 0L),
        R.string.linux_fact_free to Formatter.formatShortFileSize(context, freeBytes(context)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        facts.forEachIndexed { index, (label, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, segmentedShape(index, facts.size))
                    .padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = WhittleFonts.mono), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            linuxContent(graph, state, usedBytes = used)
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
                    item { AppBuildingRow(graph) }
                    item { ComputerAccessSection(graph) }
                    item { ManageRows(graph, used) { confirming = it } }
                }
            }
        }
    }
    confirming?.let { confirm -> ConfirmDialog(confirm, graph, onDone = { confirming = null }) }
}

/**
 * The app-building toolchain: whether it's set up (the ready marker, not just files on disk) and its size, with Remove
 * confirmed in its own dialog. A setup that never finished can be removed too; nothing can while an app is building.
 */
@Composable
private fun AppBuildingRow(graph: AppGraph) {
    val toolchain = graph.appToolchain
    val state by toolchain.state.collectAsStateWithLifecycle()
    val building by toolchain.building.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var removeFailed by remember { mutableStateOf<ToolchainFailure?>(null) }
    LaunchedEffect(state) { removeFailed = null }
    val installed by produceState<Installed?>(null, state, refresh) {
        value = if (state is ToolchainState.Installing) null else Installed(toolchain.isReady(), toolchain.size())
    }
    val ready = installed?.ready == true
    val size = installed?.size?.let { Formatter.formatShortFileSize(LocalContext.current, it) }
    val removable = canRemoveToolchain(ready, installed?.size, installing = state is ToolchainState.Installing, building = building)
    SettingsRow(
        icon = "build",
        title = stringResource(R.string.app_toolchain_row_title),
        subtitle = when {
            state is ToolchainState.Installing -> stringResource(R.string.app_toolchain_row_installing)
            building || removeFailed == ToolchainFailure.InUse -> stringResource(R.string.app_toolchain_row_building)
            removeFailed != null -> stringResource(R.string.app_toolchain_remove_failed)
            ready -> size?.let { stringResource(R.string.app_toolchain_row_installed, it) } ?: stringResource(R.string.app_toolchain_row_ready)
            size != null -> stringResource(R.string.app_toolchain_row_partial, size)
            else -> stringResource(R.string.app_toolchain_row_missing)
        },
        shape = RoundedCornerShape(WhittleRadius.l),
        // Remove is the row's one action, so it's a button, not a red row.
        trailing = if (removable) {
            { TextButton(onClick = { confirming = true }) { Text(stringResource(R.string.app_toolchain_remove)) } }
        } else {
            null
        },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.app_toolchain_remove_title)) },
            text = { Text(stringResource(R.string.app_toolchain_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    graph.appScope.launch {
                        removeFailed = toolchain.remove()
                        refresh++
                    }
                }) { Text(stringResource(R.string.app_toolchain_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.app_toolchain_remove_cancel)) } },
        )
    }
}

/** What the app-building row shows: whether setup finished, and the bytes on disk when they can be read. */
private data class Installed(val ready: Boolean, val size: Long?)

/** Repair, reset and remove, from least to most drastic. */
@Composable
private fun ManageRows(graph: AppGraph, used: Long, onConfirm: (Confirm) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        SettingsRow(
            icon = "build",
            title = stringResource(R.string.linux_repair),
            subtitle = null,
            shape = segmentedShape(0, MANAGE_ROWS),
            onClick = { LinuxSetupService.repair(context) },
        )
        SettingsRow(
            icon = "restart_alt",
            title = stringResource(R.string.linux_reset),
            subtitle = null,
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
                // Nothing may sign in to a Linux that's being replaced or removed.
                graph.computerAccess.turnOff()
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
    // One color at three strengths: the parts stay apart by lightness, whatever colors the wallpaper gives.
    val parts = listOf(
        Triple(R.string.linux_storage_base, usage.base, colors.primary),
        Triple(R.string.linux_storage_added, usage.added, colors.primary.copy(alpha = ADDED_ALPHA)),
        Triple(R.string.linux_storage_caches, usage.caches, colors.primary.copy(alpha = CACHES_ALPHA)),
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
            FilledTonalButton(onClick = onClearCaches, modifier = Modifier.fillMaxWidth()) {
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
private fun androidx.compose.foundation.lazy.LazyListScope.linuxContent(graph: AppGraph, state: LinuxState, usedBytes: Long) {
    val image = graph.linux.image
    item { IntroCard(image?.name.orEmpty(), state, usedBytes) }
    when (state) {
        LinuxState.NotSetUp -> item { Facts(image) }
        is LinuxState.SettingUp, is LinuxState.Failed -> item { Progress(state, image?.name.orEmpty()) }
        is LinuxState.Ready -> Unit
    }
}

@Composable
private fun IntroCard(imageName: String, state: LinuxState, usedBytes: Long) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val status = when (state) {
        LinuxState.NotSetUp -> stringResource(R.string.linux_status_not_set_up, imageName)
        is LinuxState.SettingUp -> stringResource(R.string.linux_status_setting_up, imageName)
        is LinuxState.Ready -> stringResource(R.string.linux_status_ready, Formatter.formatShortFileSize(context, usedBytes))
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
                Text(if (state is LinuxState.Ready) imageName else stringResource(R.string.linux_intro_title), style = MaterialTheme.typography.titleMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        // Once it's ready, the title and status say it all.
        if (state !is LinuxState.Ready) {
            Text(stringResource(R.string.linux_intro_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            WhatYouGet()
        }
    }
}

/** What Linux makes possible, in three lines. */
@Composable
private fun WhatYouGet() {
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        GETS.forEach { (icon, text) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                WhittleIcon(icon, size = WhittleSize.Icon.l, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
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

/** This run's steps so far, as in a chat: done ones ticked, the current one running or failed with its reason. */
@Composable
private fun Progress(state: LinuxState, imageName: String) {
    val context = LocalContext.current
    val (from, current) = when (state) {
        is LinuxState.SettingUp -> state.from to state.step
        is LinuxState.Failed -> state.from to state.step
        else -> return
    }
    val steps = LinuxSetupStep.entries.filter { it.ordinal in from.ordinal..current.ordinal }.map { step ->
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
private const val ADDED_ALPHA = 0.55f
private const val CACHES_ALPHA = 0.22f
private const val PERCENT = 100f
private val FACT_ICONS = listOf("download", "folder_zip", "folder")
/** What Linux makes possible, with its icons. */
private val GETS = listOf("package_2" to R.string.linux_get_apt, "memory" to R.string.linux_get_compile, "terminal" to R.string.linux_get_run)
private val STEP_ICONS = mapOf(
    LinuxSetupStep.Download to "download",
    LinuxSetupStep.Unpack to "folder_zip",
    LinuxSetupStep.Configure to "shield",
    LinuxSetupStep.BasePackages to "package_2",
)
