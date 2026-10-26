package app.whittle.linux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SkeletonBlock
import app.whittle.design.components.StepGroup
import app.whittle.design.components.StepStatus
import app.whittle.design.components.StepUi
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.window.BackHandler
import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.WindowTopBar
import app.whittle.files.ui.sizeText
import app.whittle.settings.ui.LinuxKind
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Settings › Linux workspace: set it up, or see where its space went and repair, reset or remove it. */
@Composable
fun LinuxWorkspaceScreen(host: LinuxHost, platform: LinuxPlatform, onBack: () -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    val state by host.state.collectAsState()
    var confirming by remember { mutableStateOf<Confirm?>(null) }
    // What the screen shows is read again as it opens: sizes, the toolchain, the computers and Tailscale.
    LaunchedEffect(host) { host.act(LinuxAction.Refresh) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            WindowTopBar {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(LocalWindowChrome.current?.insets ?: WindowInsets(0))
                        .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
                ) {
                    TopBarButton("arrow_back", stringResource(Res.string.settings_back), onBack)
                    Text(stringResource(Res.string.linux_title), style = MaterialTheme.typography.titleLarge)
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
        ) {
            linuxContent(state)
            when (state.kind) {
                LinuxKind.NotSetUp -> item { SetUpButton(state, host) }
                LinuxKind.Failed -> item { SetUpButton(state, host, Res.string.linux_try_again) }
                LinuxKind.SettingUp -> Unit
                LinuxKind.Ready -> {
                    item { StorageCard(state.usage) { host.act(LinuxAction.ClearCaches) } }
                    if (state.running.isNotEmpty()) item {
                        RunningRow(state.running) { host.act(LinuxAction.StopAll) }
                    }
                    item { AppBuildingRow(state.toolchain) { host.act(LinuxAction.RemoveToolchain) } }
                    item { ComputerAccessSection(state, host, platform) }
                    item { ManageRows(state.usedBytes, onRepair = { host.act(LinuxAction.Repair) }) { confirming = it } }
                }
            }
        }
    }
    confirming?.let { confirm -> ConfirmDialog(confirm, host, onDone = { confirming = null }) }
}

/**
 * The app-building toolchain: whether it's set up (the ready marker, not just files on disk) and its size, with Remove
 * confirmed in its own dialog. A setup that never finished can be removed too; nothing can while an app is building.
 */
@Composable
private fun AppBuildingRow(toolchain: Toolchain, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val size = toolchain.sizeBytes?.let(::sizeText)
    SettingsRow(
        icon = "build",
        title = stringResource(Res.string.app_toolchain_row_title),
        subtitle = when {
            toolchain.installing -> stringResource(Res.string.app_toolchain_row_installing)
            toolchain.building || toolchain.removeFailed == ToolchainIssue.InUse -> stringResource(Res.string.app_toolchain_row_building)
            toolchain.removeFailed != null -> stringResource(Res.string.app_toolchain_remove_failed)
            toolchain.ready -> size?.let { stringResource(Res.string.app_toolchain_row_installed, it) } ?: stringResource(Res.string.app_toolchain_row_ready)
            size != null -> stringResource(Res.string.app_toolchain_row_partial, size)
            else -> stringResource(Res.string.app_toolchain_row_missing)
        },
        shape = RoundedCornerShape(WhittleRadius.l),
        // Until it's read, nothing is known: not set up or set up.
        loading = !toolchain.loaded && !toolchain.installing,
        // Remove is the row's one action, so it's a button, not a red row.
        trailing = when {
            toolchain.loaded && toolchain.removable -> {
                { TextButton(onClick = { confirming = true }) { Text(stringResource(Res.string.app_toolchain_remove)) } }
            }
            // Where Remove usually lands, so the row keeps its height when it does.
            !toolchain.loaded && !toolchain.installing -> {
                { Box(Modifier.height(WhittleSize.touchTarget), contentAlignment = Alignment.Center) { SkeletonBlock(Modifier.width(WhittleSize.Tile.xl).height(WhittleSpace.l)) } }
            }
            else -> null
        },
    )
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(Res.string.app_toolchain_remove_title)) },
            text = { Text(stringResource(Res.string.app_toolchain_remove_body)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onRemove() }) { Text(stringResource(Res.string.app_toolchain_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.app_toolchain_remove_cancel)) } },
        )
    }
}

/** Repair, reset and remove, from least to most drastic. */
@Composable
private fun ManageRows(used: Long?, onRepair: () -> Unit, onConfirm: (Confirm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        SettingsRow(
            icon = "build",
            title = stringResource(Res.string.linux_repair),
            subtitle = null,
            shape = segmentedShape(0, MANAGE_ROWS),
            onClick = onRepair,
        )
        SettingsRow(
            icon = "restart_alt",
            title = stringResource(Res.string.linux_reset),
            subtitle = null,
            shape = segmentedShape(1, MANAGE_ROWS),
            onClick = { onConfirm(Confirm.Reset) },
        )
        SettingsRow(
            icon = "delete",
            title = stringResource(Res.string.linux_remove),
            subtitle = used?.let { stringResource(Res.string.linux_remove_body, sizeText(it)) },
            loading = used == null,
            shape = segmentedShape(2, MANAGE_ROWS),
            destructive = true,
            onClick = { onConfirm(Confirm.Remove) },
        )
    }
}

@Composable
private fun ConfirmDialog(confirm: Confirm, host: LinuxHost, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(confirm.title)) },
        text = { Text(stringResource(confirm.body)) },
        confirmButton = {
            TextButton(onClick = {
                onDone()
                host.act(confirm.action)
            }) { Text(stringResource(confirm.label), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(Res.string.connector_cancel)) } },
    )
}

/**
 * Where the space went, as one bar, with the caches clearable. Until [usage] is measured (null), the same layout shows
 * with skeleton blocks where the bar, the sizes and the button go, so nothing jumps when they arrive.
 */
@Composable
private fun StorageCard(usage: LinuxUsage?, onClearCaches: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // One color at three strengths: the parts stay apart by lightness, whatever colors the wallpaper gives.
    val parts = listOf(
        Triple(Res.string.linux_storage_base, usage?.base, colors.primary),
        Triple(Res.string.linux_storage_added, usage?.added, colors.primary.copy(alpha = ADDED_ALPHA)),
        Triple(Res.string.linux_storage_caches, usage?.caches, colors.primary.copy(alpha = CACHES_ALPHA)),
    )
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl)).padding(WhittleSpace.l),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        if (usage == null) {
            SkeletonBlock(Modifier.fillMaxWidth().height(WhittleSpace.m))
        } else {
            Row(Modifier.fillMaxWidth().height(WhittleSpace.m).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                parts.mapNotNull { (_, bytes, color) -> bytes?.takeIf { it > 0 }?.let { it to color } }.forEach { (bytes, color) ->
                    Box(Modifier.weight(bytes.toFloat().coerceAtLeast(MIN_WEIGHT)).fillMaxHeight().background(color))
                }
            }
        }
        parts.forEach { (label, bytes, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                Box(Modifier.size(WhittleSpace.m).background(color, RoundedCornerShape(WhittleRadius.xs)))
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (bytes != null) {
                    Text(sizeText(bytes), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                } else {
                    SkeletonBlock(Modifier.width(WhittleSize.Tile.xl).height(WhittleSpace.l))
                }
            }
        }
        if (usage == null) {
            SkeletonBlock(Modifier.fillMaxWidth().height(WhittleSize.Button.default))
        } else if (usage.caches > 0) {
            FilledTonalButton(onClick = onClearCaches, modifier = Modifier.fillMaxWidth()) {
                WhittleIcon("block", size = WhittleSize.Icon.m)
                Text(stringResource(Res.string.linux_clear_caches, sizeText(usage.caches)), modifier = Modifier.padding(start = WhittleSpace.s))
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
            Text(pluralStringResource(Res.plurals.linux_processes_running, names.size, names.size), style = MaterialTheme.typography.titleSmall, color = colors.onSecondaryContainer)
            Text(names.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer, maxLines = 1)
        }
        TextButton(onClick = onStopAll) { Text(stringResource(Res.string.notify_stop_all)) }
    }
}

private enum class Confirm(val title: StringResource, val body: StringResource, val label: StringResource, val action: LinuxAction) {
    Reset(Res.string.linux_reset_title, Res.string.linux_reset_confirm_body, Res.string.linux_reset, LinuxAction.Reset),
    Remove(Res.string.linux_remove_title, Res.string.linux_remove_confirm_body, Res.string.linux_remove, LinuxAction.Remove),
}

/** What Linux is and, by state, its sizes, its setup steps or that it's ready. */
private fun LazyListScope.linuxContent(state: LinuxScreenState) {
    item { IntroCard(state) }
    when (state.kind) {
        LinuxKind.NotSetUp -> state.facts?.let { facts -> item { Facts(facts) } }
        LinuxKind.SettingUp, LinuxKind.Failed -> state.progress?.let { progress -> item { Progress(progress) } }
        LinuxKind.Ready -> Unit
    }
}

@Composable
private fun IntroCard(state: LinuxScreenState) {
    val colors = MaterialTheme.colorScheme
    val image = state.imageName
    val status = when (state.kind) {
        LinuxKind.NotSetUp -> stringResource(Res.string.linux_status_not_set_up, image)
        LinuxKind.SettingUp -> stringResource(Res.string.linux_status_setting_up, image)
        LinuxKind.Ready -> state.usedBytes?.let { stringResource(Res.string.linux_status_ready, sizeText(it)) }
        LinuxKind.Failed -> stringResource(Res.string.linux_status_failed, image)
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
                Text(if (state.kind == LinuxKind.Ready) image else stringResource(Res.string.linux_intro_title), style = MaterialTheme.typography.titleMedium)
                if (status != null) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else {
                    SkeletonBlock(Modifier.padding(vertical = WhittleSpace.hairline).width(WhittleSize.Tile.xl * 2).height(WhittleSpace.m))
                }
            }
        }
        // Once it's ready, the title and status say it all.
        if (state.kind != LinuxKind.Ready) {
            Text(stringResource(Res.string.linux_intro_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
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
private fun Facts(facts: SetupFacts) {
    val rows = listOf(
        Res.string.linux_fact_download to sizeText(facts.downloadBytes),
        Res.string.linux_fact_start to sizeText(facts.setupBytes),
        Res.string.linux_fact_free to sizeText(facts.freeBytes),
    )
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.l)) {
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
            rows.forEachIndexed { index, (label, value) ->
                SettingsRow(icon = FACT_ICONS[index], title = stringResource(label), subtitle = null, shape = segmentedShape(index, rows.size), trailing = {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m), modifier = Modifier.padding(horizontal = WhittleSpace.xs)) {
            WhittleIcon("wifi", size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.linux_wifi_tip), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** This run's steps so far, as in a chat: done ones ticked, the current one running or failed with its reason. */
@Composable
private fun Progress(progress: SetupProgress) {
    val steps = progress.steps.map { step ->
        StepUi(
            id = step.id,
            icon = step.icon,
            label = step.label,
            detail = step.detail,
            status = when (step.status) {
                SetupStepStatus.Done -> StepStatus.Done
                SetupStepStatus.Running -> StepStatus.Running
                SetupStepStatus.Failed -> StepStatus.Failed
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
        StepGroup(steps)
        if (progress.running) {
            val percent = progress.percent
            if (percent != null) {
                LinearProgressIndicator(progress = { percent / PERCENT }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(stringResource(Res.string.linux_keeps_going), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SetUpButton(state: LinuxScreenState, host: LinuxHost, label: StringResource = Res.string.linux_set_up) {
    Button(
        onClick = { host.act(LinuxAction.SetUp) },
        enabled = state.imageAvailable,
        modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.default),
    ) {
        WhittleIcon("download", size = WhittleSize.Icon.l)
        Text(stringResource(label), modifier = Modifier.padding(start = WhittleSpace.s))
    }
}

private const val MANAGE_ROWS = 3

/** Keeps a small part visible in the bar. */
private const val MIN_WEIGHT = 1f
private const val ADDED_ALPHA = 0.55f
private const val CACHES_ALPHA = 0.22f
private const val PERCENT = 100f
private val FACT_ICONS = listOf("download", "folder_zip", "folder")

/** What Linux makes possible, with its icons. */
private val GETS = listOf("package_2" to Res.string.linux_get_apt, "memory" to Res.string.linux_get_compile, "terminal" to Res.string.linux_get_run)
