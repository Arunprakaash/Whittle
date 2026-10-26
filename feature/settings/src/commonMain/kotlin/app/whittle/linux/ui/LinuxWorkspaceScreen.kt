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
import androidx.compose.ui.graphics.Color
import app.whittle.design.components.BeaverScene
import app.whittle.design.components.MenuAction
import app.whittle.design.components.SceneProgress
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SkeletonBlock
import app.whittle.design.components.StepGroup
import app.whittle.design.components.StepStatus
import app.whittle.design.components.StepUi
import app.whittle.design.components.TopBarAction
import app.whittle.design.components.TopBarActionGroup
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.TopBarGroupAction
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.connectedShape
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.CreationTone
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
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Linux workspace: the beaver's scene with how Linux stands, then by state what setup brings, how setup is
 * going, or the computers that may sign in, how they do, what's running, where the space went and app building. The
 * rare, drastic actions (repair, reset, remove) are in the top bar's menu.
 */
@Composable
fun LinuxWorkspaceScreen(host: LinuxHost, platform: LinuxPlatform, onBack: () -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    val state by host.state.collectAsState()
    var confirming by remember { mutableStateOf<Confirm?>(null) }
    // What the screen shows is read again as it opens: sizes, the toolchain, the computers and Tailscale.
    LaunchedEffect(host) { host.act(LinuxAction.Refresh) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar(state, onBack, onRepair = { host.act(LinuxAction.Repair) }, onConfirm = { confirming = it }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = WhittleSpace.xl),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl),
        ) {
            item { BeaverScene(state.imageName, sceneStatus(state), working = state.kind == LinuxKind.SettingUp, progress = sceneProgress(state)) }
            when (state.kind) {
                LinuxKind.NotSetUp -> notSetUp(state, host)
                LinuxKind.SettingUp -> state.progress?.let { progress -> padded { Progress(progress) } }
                LinuxKind.Failed -> {
                    state.progress?.let { progress -> padded { Progress(progress) } }
                    padded { SetUpButton(state, host, Res.string.linux_try_again) }
                }
                LinuxKind.Ready -> {
                    padded { ComputersSection(state.access, host) }
                    padded { AccessSection(state, host, platform) }
                    if (state.running.isNotEmpty()) padded { RunningSection(state.running) { host.act(LinuxAction.StopAll) } }
                    padded { StorageSection(state.usage) { host.act(LinuxAction.ClearCaches) } }
                    padded { AppBuildingSection(state.toolchain) { host.act(LinuxAction.RemoveToolchain) } }
                }
            }
        }
    }
    confirming?.let { confirm -> ConfirmDialog(confirm, host, onDone = { confirming = null }) }
}

/** An item inside the page's side margins; the scene alone runs edge to edge. */
private fun LazyListScope.padded(content: @Composable () -> Unit) {
    item { Box(Modifier.padding(horizontal = WhittleSpace.l)) { content() } }
}

@Composable
private fun TopBar(state: LinuxScreenState, onBack: () -> Unit, onRepair: () -> Unit, onConfirm: (Confirm) -> Unit) {
    WindowTopBar {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(LocalWindowChrome.current?.insets ?: WindowInsets(0))
                .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
        ) {
            TopBarButton("arrow_back", stringResource(Res.string.settings_back), onBack)
            Text(stringResource(Res.string.linux_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
            if (state.kind == LinuxKind.Ready) MoreMenu(state, onRepair, onConfirm)
        }
    }
}

/** Repair, reset and remove, from least to most drastic; and Tailscale's removal while it's set up but signed out. */
@Composable
private fun MoreMenu(state: LinuxScreenState, onRepair: () -> Unit, onConfirm: (Confirm) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val removeLabel = state.usedBytes?.let { stringResource(Res.string.linux_remove_menu, sizeText(it)) } ?: stringResource(Res.string.linux_remove)
    val linux = listOf(
        MenuAction(stringResource(Res.string.linux_repair), "build", onRepair),
        MenuAction(stringResource(Res.string.linux_reset), "restart_alt", { onConfirm(Confirm.Reset) }),
    )
    val tailscale = if (state.tailscale.kind == TailscaleKind.SignedOut) listOf(MenuAction(stringResource(Res.string.tailscale_remove_menu), "tailscale", { onConfirm(Confirm.RemoveTailscale) })) else emptyList()
    Box {
        TopBarActionGroup {
            TopBarAction("more_vert", stringResource(Res.string.linux_more), onClick = { open = true }, shape = connectedShape(0, 1), modifier = TopBarGroupAction)
        }
        WhittleMenu(
            expanded = open,
            onDismiss = { open = false },
            groups = listOfNotNull(linux, tailscale.takeIf { it.isNotEmpty() }, listOf(MenuAction(removeLabel, "delete", { onConfirm(Confirm.Remove) }, destructive = true))),
        )
    }
}

@Composable
private fun sceneStatus(state: LinuxScreenState): String? = when (state.kind) {
    LinuxKind.NotSetUp -> stringResource(Res.string.linux_scene_not_set_up)
    LinuxKind.SettingUp -> state.progress?.steps?.lastOrNull()?.detail?.let { stringResource(Res.string.linux_scene_setting_up_detail, it) }
        ?: stringResource(Res.string.linux_scene_setting_up)
    LinuxKind.Ready -> state.usedBytes?.let { stringResource(Res.string.linux_status_ready, sizeText(it)) }
    LinuxKind.Failed -> stringResource(Res.string.linux_scene_failed)
}

private fun sceneProgress(state: LinuxScreenState): SceneProgress =
    state.progress?.takeIf { it.running && state.kind == LinuxKind.SettingUp }?.percent?.let { SceneProgress.Done(it / PERCENT) } ?: SceneProgress.None

/** Before setup: what Linux makes possible, what it takes, and the button. */
private fun LazyListScope.notSetUp(state: LinuxScreenState, host: LinuxHost) {
    padded {
        SettingsSection(stringResource(Res.string.linux_get_title)) {
            GETS.forEachIndexed { index, (icon, text) ->
                SettingsRow(icon = icon, title = stringResource(text), subtitle = null, shape = segmentedShape(index, GETS.size))
            }
        }
    }
    state.facts?.let { facts -> padded { Facts(facts) } }
    padded { SetUpButton(state, host) }
}

@Composable
private fun Facts(facts: SetupFacts) {
    val rows = listOf(
        Res.string.linux_fact_download to sizeText(facts.downloadBytes),
        Res.string.linux_fact_start to sizeText(facts.setupBytes),
        Res.string.linux_fact_free to sizeText(facts.freeBytes),
    )
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
        SettingsSection(stringResource(Res.string.linux_facts_title)) {
            rows.forEachIndexed { index, (label, value) ->
                SettingsRow(icon = FACT_ICONS[index], title = stringResource(label), subtitle = null, shape = segmentedShape(index, rows.size), trailing = {
                    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                })
            }
        }
        Tip("wifi", stringResource(Res.string.linux_wifi_tip))
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
        if (progress.running) Tip("info", stringResource(Res.string.linux_keeps_going))
    }
}

@Composable
private fun Tip(icon: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m), modifier = Modifier.padding(horizontal = WhittleSpace.m)) {
        WhittleIcon(icon, size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** What the agent has running in Linux, each with a dot: green once it serves, grey while it starts. */
@Composable
private fun RunningSection(running: List<RunningProcess>, onStopAll: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val live = liveColor()
    SettingsSection(
        stringResource(Res.string.linux_running),
        action = { TextButton(onClick = onStopAll) { Text(stringResource(Res.string.notify_stop_all)) } },
    ) {
        running.forEach { process ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.l),
            ) {
                Box(Modifier.size(WhittleSpace.m).clip(CircleShape).background(if (process.starting) colors.outline else live))
                Column {
                    Text(process.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            process.starting -> stringResource(Res.string.linux_running_starting)
                            process.port != null -> stringResource(Res.string.linux_running_port, process.port.toString())
                            else -> stringResource(Res.string.linux_running)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Where the space went, as one bar with each part's size under it, and Clear caches on the title's line. Until [usage]
 * is measured (null), skeleton blocks stand where the bar and the sizes go, so nothing jumps when they arrive.
 */
@Composable
private fun StorageSection(usage: LinuxUsage?, onClearCaches: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // One color at three strengths: the parts stay apart by lightness, whatever colors the wallpaper gives.
    val parts = listOf(
        Triple(Res.string.linux_storage_base, usage?.base, colors.primary),
        Triple(Res.string.linux_storage_added, usage?.added, colors.primary.copy(alpha = ADDED_ALPHA)),
        Triple(Res.string.linux_storage_caches, usage?.caches, colors.primary.copy(alpha = CACHES_ALPHA)),
    )
    SettingsSection(
        stringResource(Res.string.linux_storage),
        action = if (usage != null && usage.caches > 0) {
            { TextButton(onClick = onClearCaches) { Text(stringResource(Res.string.linux_clear_caches_short)) } }
        } else null,
    ) {
        Column(Modifier.padding(horizontal = WhittleSpace.xs), verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            if (usage == null) {
                SkeletonBlock(Modifier.fillMaxWidth().height(WhittleSpace.s))
            } else {
                Row(Modifier.fillMaxWidth().height(WhittleSpace.s).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                    parts.mapNotNull { (_, bytes, color) -> bytes?.takeIf { it > 0 }?.let { it to color } }.forEach { (bytes, color) ->
                        Box(Modifier.weight(bytes.toFloat().coerceAtLeast(MIN_WEIGHT)).fillMaxHeight().background(color))
                    }
                }
            }
            parts.forEach { (label, bytes, color) ->
                Row(Modifier.padding(horizontal = WhittleSpace.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.l)) {
                    Box(Modifier.size(WhittleSpace.m).background(color, RoundedCornerShape(WhittleRadius.xs)))
                    Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (bytes != null) {
                        Text(sizeText(bytes), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    } else {
                        SkeletonBlock(Modifier.width(WhittleSize.Tile.xl).height(WhittleSpace.l))
                    }
                }
            }
        }
    }
}

/**
 * The app-building toolchain: whether it's set up (the ready marker, not just files on disk) and its size, with Remove
 * confirmed in its own dialog. A setup that never finished can be removed too; nothing can while an app is building.
 */
@Composable
private fun AppBuildingSection(toolchain: Toolchain, onRemove: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    val size = toolchain.sizeBytes?.let(::sizeText)
    // Its title has no action, so it gets the room the other titles' actions give them, and the gaps stay even.
    SettingsSection(stringResource(Res.string.app_toolchain_row_title), Modifier.padding(top = TITLE_ACTION_ROOM)) {
        SettingsRow(
            icon = "build",
            title = stringResource(Res.string.linux_toolchain_name),
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
    }
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

/** What the top bar's menu asks before doing: each can't be undone. */
private enum class Confirm(val title: StringResource, val body: StringResource, val label: StringResource, val action: LinuxAction) {
    Reset(Res.string.linux_reset_title, Res.string.linux_reset_confirm_body, Res.string.linux_reset, LinuxAction.Reset),
    Remove(Res.string.linux_remove_title, Res.string.linux_remove_confirm_body, Res.string.linux_remove, LinuxAction.Remove),
    RemoveTailscale(Res.string.tailscale_remove_title, Res.string.tailscale_remove_body, Res.string.tailscale_remove, LinuxAction.TailscaleSignOut(remove = true)),
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

/** Green for what's live (a process serving, a computer signed in): Whittle's pine. */
@Composable
internal fun liveColor(): Color = WhittleTheme.creationColors.getValue(CreationTone.Pine).fill

private const val PERCENT = 100f

/** What a title's action adds above it: half a touch target less half a line of label text. */
private val TITLE_ACTION_ROOM = WhittleSpace.m

/** Keeps a small part visible in the bar. */
private const val MIN_WEIGHT = 1f
private const val ADDED_ALPHA = 0.55f
private const val CACHES_ALPHA = 0.22f
private val FACT_ICONS = listOf("download", "folder_zip", "folder")

/** What Linux makes possible, with its icons. */
private val GETS = listOf("package_2" to Res.string.linux_get_apt, "memory" to Res.string.linux_get_compile, "terminal" to Res.string.linux_get_run)
