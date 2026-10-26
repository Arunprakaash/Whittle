package app.whittle.linux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import app.whittle.design.components.CopyableText
import app.whittle.design.components.PillChip
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.window.plainTextClip
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The computers that may sign in, as chips; + adds one, and a chip opens that computer's sheet. */
@Composable
internal fun ComputersSection(access: ComputerAccess, host: LinuxHost) {
    var adding by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<ComputerRow?>(null) }
    SettingsSection(
        stringResource(Res.string.linux_computers),
        action = {
            IconButton(onClick = { adding = true }) {
                WhittleIcon("add", size = WhittleSize.Icon.l, tint = MaterialTheme.colorScheme.onSurface, contentDescription = stringResource(Res.string.computer_add))
            }
        },
    ) {
        if (access.computers.isEmpty()) {
            Text(
                stringResource(Res.string.linux_computers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = WhittleSpace.m),
            )
        } else {
            ChipRow {
                access.computers.forEach { computer ->
                    PillChip("laptop_mac", computer.comment.ifBlank { stringResource(Res.string.computer_unnamed) }, onClick = { open = computer })
                }
            }
        }
    }
    if (adding) AddComputerSheet(access, host, onDone = { adding = false })
    open?.let { computer -> ComputerSheet(computer, onRemove = { host.act(LinuxAction.RemoveComputer(computer.fingerprint)) }, onDone = { open = null }) }
}

/**
 * Using Linux from a computer: the switch on the title's line, the ways in (Wi-Fi, Tailscale) as chips and the command
 * a computer types to sign in. Turned off, they dim and stay where they are: nothing on the page moves.
 */
@Composable
internal fun AccessSection(state: LinuxScreenState, host: LinuxHost, platform: LinuxPlatform) {
    val access = state.access
    var signingOut by remember { mutableStateOf(false) }
    val on = access.kind == AccessKind.On
    val busy = access.kind == AccessKind.Installing || access.kind == AccessKind.Starting
    SettingsSection(
        stringResource(Res.string.computer_switch),
        description = when (access.kind) {
            AccessKind.Installing -> stringResource(Res.string.computer_installing)
            AccessKind.Starting -> stringResource(Res.string.computer_starting)
            AccessKind.Failed -> access.detail?.let { stringResource(Res.string.computer_failed_detail, it) } ?: stringResource(Res.string.computer_failed)
            AccessKind.Off -> if (!access.reachable) stringResource(Res.string.computer_needs_network) else null
            AccessKind.On -> null
        },
        action = {
            Switch(
                checked = on || busy,
                onCheckedChange = { turnOn -> host.act(LinuxAction.SetAccess(turnOn)) },
                enabled = on || busy || access.reachable,
                modifier = Modifier.padding(end = WhittleSpace.xs),
            )
        },
    ) {
        ChipRow {
            PillChip(
                "wifi",
                access.wifiAddress?.let { stringResource(Res.string.computer_wifi_chip, it) } ?: stringResource(Res.string.computer_wifi_none),
                dim = !on || access.wifiAddress == null,
            )
            TailscaleChip(state.tailscale, platform.opensLinksItself, onAct = host::act, onSignOut = { signingOut = true })
        }
        val commands = listOfNotNull(access.signInWifi, access.signInTailscale)
        // The commands sit a step further from the chips than the chips from the title: they're what you copy.
        if (commands.isNotEmpty()) {
            Column(Modifier.padding(top = WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                commands.forEach { command -> SignInCommand(command, on) }
            }
        }
    }
    if (signingOut) SignOutDialog(onDone = { signingOut = false }) { remove -> host.act(LinuxAction.TailscaleSignOut(remove)) }
}

/** Chips on one line that scrolls, so a longer label never pushes the next chip onto a new line. */
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = WhittleSpace.xs),
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) { content() }
}

/** What a computer types to sign in, with a copy button; dimmed, and not copied, while the switch is off. */
@Composable
private fun SignInCommand(command: String, on: Boolean) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    CopyableText(
        text = command,
        copyDescription = stringResource(Res.string.computer_copy),
        onCopy = { if (on) scope.launch { clipboard.setClipEntry(plainTextClip(command, command)) } },
        code = true,
        modifier = Modifier.alpha(if (on) 1f else DIM_ALPHA),
    )
}

/**
 * Tailscale as a chip: tapping does what it's waiting for (set up, sign in, open the sign-in link, try again), and once
 * signed in shows the phone's name on the tailnet and offers Sign out. While it works, a spinner stands for its icon.
 */
@Composable
private fun TailscaleChip(row: TailscaleRow, opensLinksItself: Boolean, onAct: (LinuxAction) -> Unit, onSignOut: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val openLink = { url: String ->
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalStateException) {
            // No browser; the chip still offers the link.
        }
    }
    // Each new link opens once by itself where the device may; the chip opens it again.
    val waiting = row.url.takeIf { row.kind == TailscaleKind.WaitingForSignIn }
    LaunchedEffect(waiting) { if (opensLinksItself) waiting?.let(openLink) }
    val name = row.name.orEmpty()
    val (word, onClick) = when (row.kind) {
        TailscaleKind.Checking -> null to null
        TailscaleKind.NotInstalled -> stringResource(Res.string.tailscale_set_up) to { onAct(LinuxAction.TailscaleInstall) }
        TailscaleKind.Installing -> stringResource(Res.string.tailscale_setting_up) to null
        TailscaleKind.SignedOut -> stringResource(Res.string.tailscale_sign_in) to { onAct(LinuxAction.TailscaleSignIn) }
        TailscaleKind.SignedIn, TailscaleKind.Connected -> name to onSignOut
        TailscaleKind.Connecting -> stringResource(Res.string.tailscale_connecting) to null
        TailscaleKind.SigningOut -> stringResource(Res.string.tailscale_signing_out) to null
        TailscaleKind.Removing -> stringResource(Res.string.tailscale_removing) to null
        TailscaleKind.WaitingForSignIn -> stringResource(Res.string.tailscale_open_link) to waiting?.let { url -> { openLink(url) } }
        TailscaleKind.Failed -> stringResource(Res.string.tailscale_try_again) to { onAct(LinuxAction.TailscaleRetry) }
    }
    PillChip(
        "tailscale",
        word?.let { stringResource(Res.string.tailscale_chip, it) } ?: stringResource(Res.string.tailscale_title),
        dim = row.kind != TailscaleKind.Connected,
        working = row.kind in WORKING,
        onClick = onClick,
    )
}

/** One computer, in a sheet over the page: its name, its key's type and fingerprint, and Remove, confirmed first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComputerSheet(computer: ComputerRow, onRemove: () -> Unit, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var confirming by remember { mutableStateOf(false) }
    val name = computer.comment.ifBlank { stringResource(Res.string.computer_unnamed) }
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = WhittleSpace.l, end = WhittleSpace.l, bottom = WhittleSpace.xl),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.l), modifier = Modifier.padding(horizontal = WhittleSpace.xs)) {
                Box(Modifier.size(WhittleSize.Tile.xl).background(colors.surfaceContainerHigh, RoundedCornerShape(WhittleRadius.l)), contentAlignment = Alignment.Center) {
                    WhittleIcon("laptop_mac", tint = colors.onSurface)
                }
                Column {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(Res.string.computer_signs_in), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            SettingsSection(stringResource(Res.string.computer_key)) {
                SettingsRow(icon = "key", title = stringResource(Res.string.computer_key_type), subtitle = null, shape = segmentedShape(0, KEY_ROWS), trailing = {
                    Text(computer.type, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                })
                SettingsRow(
                    icon = "fingerprint",
                    title = stringResource(Res.string.computer_key_fingerprint),
                    subtitle = computer.fingerprint,
                    shape = segmentedShape(1, KEY_ROWS),
                    trailing = {
                        IconButton(onClick = { scope.launch { clipboard.setClipEntry(plainTextClip(computer.fingerprint, computer.fingerprint)) } }) {
                            WhittleIcon("content_copy", size = WhittleSize.Icon.l, contentDescription = stringResource(Res.string.computer_copy))
                        }
                    },
                )
            }
            SettingsRow(
                icon = "delete",
                title = stringResource(Res.string.computer_remove_this),
                subtitle = stringResource(Res.string.computer_remove_this_body),
                shape = RoundedCornerShape(WhittleRadius.lIncreased),
                destructive = true,
                onClick = { confirming = true },
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(Res.string.computer_remove_confirm_title, name)) },
            text = { Text(stringResource(Res.string.computer_remove_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onRemove(); onDone() }) { Text(stringResource(Res.string.computer_remove), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(Res.string.computer_cancel)) } },
        )
    }
}

/** Sign out, and if the user asks, remove Tailscale too. */
@Composable
private fun SignOutDialog(onDone: () -> Unit, onSignOut: (remove: Boolean) -> Unit) {
    var remove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(Res.string.tailscale_sign_out_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                Text(stringResource(Res.string.tailscale_sign_out_body))
                Row(
                    Modifier.fillMaxWidth().toggleable(value = remove, role = Role.Checkbox) { remove = it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
                ) {
                    Checkbox(checked = remove, onCheckedChange = null)
                    Text(stringResource(Res.string.tailscale_remove_too))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(); onSignOut(remove) }) { Text(stringResource(Res.string.tailscale_sign_out), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(Res.string.computer_cancel)) } },
    )
}

/** Paste a computer's public key: the command that prints it, the field, and why a key wasn't taken. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddComputerSheet(access: ComputerAccess, host: LinuxHost, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var key by remember { mutableStateOf("") }
    var issue by remember { mutableStateOf<KeyIssue?>(null) }
    var saving by remember { mutableStateOf(false) }
    val printKey = access.printKeyCommand

    fun add() {
        saving = true
        scope.launch {
            issue = host.addComputer(key)
            saving = false
            if (issue == null) onDone()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDone() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = WhittleSpace.xxl),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
                    Text(stringResource(Res.string.computer_sheet_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(Res.string.computer_sheet_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                CopyableText(
                    text = printKey,
                    copyDescription = stringResource(Res.string.computer_copy),
                    onCopy = { scope.launch { clipboard.setClipEntry(plainTextClip(printKey, printKey)) } },
                    code = true,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; issue = null },
                    label = { Text(stringResource(Res.string.computer_key_label)) },
                    textStyle = TextStyle(fontFamily = WhittleFonts.mono),
                    minLines = KEY_LINES,
                    isError = issue != null,
                    supportingText = issue?.let { { Text(issueText(it, access.maxComputers)) } },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(Res.string.computer_no_key, access.makeKeyCommand), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = WhittleSpace.xxl, vertical = WhittleSpace.m),
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDone, enabled = !saving) { Text(stringResource(Res.string.computer_cancel)) }
                Button(onClick = ::add, enabled = key.isNotBlank() && !saving, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.computer_add_action))
                }
            }
        }
    }
}

@Composable
private fun issueText(issue: KeyIssue, max: Int): String = when (issue) {
    KeyIssue.NotAKey -> stringResource(Res.string.computer_error_not_a_key)
    KeyIssue.TypeNotAllowed -> stringResource(Res.string.computer_error_type)
    KeyIssue.AlreadyAdded -> stringResource(Res.string.computer_error_added)
    KeyIssue.TooMany -> pluralStringResource(Res.plurals.computer_error_too_many, max, max)
    KeyIssue.NoAnswer -> stringResource(Res.string.computer_error_no_answer)
}

/** Tailscale's states that are under way: the chip shows a spinner and waits. */
private val WORKING = setOf(TailscaleKind.Installing, TailscaleKind.Connecting, TailscaleKind.SigningOut, TailscaleKind.Removing)

private const val DIM_ALPHA = 0.45f
private const val KEY_ROWS = 2
private const val KEY_LINES = 3
