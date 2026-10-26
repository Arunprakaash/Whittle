package app.whittle.linux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import app.whittle.design.components.CopyableText
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.window.plainTextClip
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Settings › Linux workspace › Your computer: the switch, Tailscale, and while it's on, how to sign in (on the same
 * Wi-Fi, and from anywhere over Tailscale), the computers that may, and Add a computer. It turns on with Wi-Fi, or
 * with Tailscale signed in.
 */
@Composable
internal fun ComputerAccessSection(state: LinuxScreenState, host: LinuxHost, platform: LinuxPlatform) {
    val access = state.access
    var adding by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val on = access.kind == AccessKind.On
    val busy = access.kind == AccessKind.Installing || access.kind == AccessKind.Starting
    // The switch and Tailscale, then while it's on: how to sign in, each computer, and Add a computer.
    val signIns = if (on) listOfNotNull(access.signInWifi?.let { it to Res.string.computer_same_wifi }, access.signInTailscale?.let { it to Res.string.computer_anywhere }) else emptyList()
    val rows = 2 + if (on) signIns.size + access.computers.size + 1 else 0
    SettingsSection(
        stringResource(Res.string.computer_title),
        footer = stringResource(
            when {
                on && access.tailscale != null -> Res.string.computer_footer_tailscale
                on -> Res.string.computer_footer_on
                else -> Res.string.computer_footer_off
            },
        ),
    ) {
        SettingsToggleRow(
            icon = "computer",
            title = stringResource(Res.string.computer_switch),
            subtitle = when (access.kind) {
                AccessKind.Installing -> stringResource(Res.string.computer_installing)
                AccessKind.Starting -> stringResource(Res.string.computer_starting)
                AccessKind.On -> when {
                    access.wifi != null && access.tailscale != null -> stringResource(Res.string.computer_on_both)
                    access.wifi != null -> stringResource(Res.string.computer_on, access.wifi)
                    else -> stringResource(Res.string.computer_on_tailscale)
                }
                AccessKind.Failed -> access.detail?.let { stringResource(Res.string.computer_failed_detail, it) } ?: stringResource(Res.string.computer_failed)
                AccessKind.Off -> if (!access.reachable) stringResource(Res.string.computer_needs_network) else null
            },
            checked = on || busy,
            onCheckedChange = { turnOn -> host.act(LinuxAction.SetAccess(turnOn)) },
            shape = segmentedShape(0, rows),
            enabled = on || busy || access.reachable,
        )
        TailscaleRow(
            row = state.tailscale,
            opensLinksItself = platform.opensLinksItself,
            shape = segmentedShape(1, rows),
            onAct = host::act,
            onSignOut = { signingOut = true },
            onRemove = { removing = true },
        )
        if (on) {
            signIns.forEachIndexed { index, (command, where) -> SignInRow(command, stringResource(where), segmentedShape(2 + index, rows)) }
            access.computers.forEachIndexed { index, computer ->
                SettingsRow(
                    icon = "computer",
                    title = computer.comment.ifBlank { stringResource(Res.string.computer_unnamed) },
                    subtitle = computer.fingerprint.take(FINGERPRINT_CHARS) + "…",
                    shape = segmentedShape(2 + signIns.size + index, rows),
                    trailing = { TextButton(onClick = { host.act(LinuxAction.RemoveComputer(computer.fingerprint)) }) { Text(stringResource(Res.string.computer_remove)) } },
                )
            }
            SettingsRow(
                icon = "add",
                title = stringResource(Res.string.computer_add),
                subtitle = null,
                shape = segmentedShape(rows - 1, rows),
                onClick = { adding = true },
            )
        }
    }
    if (adding) AddComputerSheet(access, host, onDone = { adding = false })
    if (signingOut) SignOutDialog(onDone = { signingOut = false }) { remove -> host.act(LinuxAction.TailscaleSignOut(remove)) }
    if (removing) RemoveDialog(onDone = { removing = false }) { host.act(LinuxAction.TailscaleSignOut(remove = true)) }
}

/** What a computer types to sign in, with where it works and a copy button. */
@Composable
private fun SignInRow(command: String, where: String, shape: Shape) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    SettingsRow(
        icon = "terminal",
        title = command,
        subtitle = where,
        shape = shape,
        trailing = {
            IconButton(onClick = { scope.launch { clipboard.setClipEntry(plainTextClip(command, command)) } }) {
                WhittleIcon("content_copy", size = WhittleSize.Icon.l, contentDescription = stringResource(Res.string.computer_copy))
            }
        },
    )
}

/**
 * Tailscale's row: set it up, sign in (on the phone the link opens in the browser by itself, and again from Open link),
 * and once connected, the phone's name on the tailnet with Sign out.
 */
@Composable
private fun TailscaleRow(
    row: TailscaleRow,
    opensLinksItself: Boolean,
    shape: Shape,
    onAct: (LinuxAction) -> Unit,
    onSignOut: () -> Unit,
    onRemove: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val openLink = { url: String ->
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalStateException) {
            // No browser; the row still offers the link.
        }
    }
    // Each new link opens once by itself where the device may; Open link opens it again.
    val waiting = row.url.takeIf { row.kind == TailscaleKind.WaitingForSignIn }
    LaunchedEffect(waiting) { if (opensLinksItself) waiting?.let(openLink) }
    val button = @Composable { label: StringResource, onClick: () -> Unit -> TextButton(onClick = onClick) { Text(stringResource(label)) } }
    // While it works, a spinner where its button goes, as the Version row does while updating.
    val working = @Composable { CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline) }
    val name = row.name.orEmpty()
    val (subtitle, trailing) = when (row.kind) {
        TailscaleKind.Checking -> null to null
        TailscaleKind.NotInstalled -> stringResource(Res.string.tailscale_pitch) to @Composable { button(Res.string.tailscale_set_up) { onAct(LinuxAction.TailscaleInstall) } }
        TailscaleKind.Installing -> stringResource(Res.string.tailscale_setting_up) to working
        TailscaleKind.SignedOut -> stringResource(Res.string.tailscale_signed_out) to @Composable { button(Res.string.tailscale_sign_in) { onAct(LinuxAction.TailscaleSignIn) } }
        TailscaleKind.SignedIn -> stringResource(Res.string.tailscale_signed_in, name) to @Composable { button(Res.string.tailscale_sign_out, onSignOut) }
        TailscaleKind.Connecting -> stringResource(Res.string.tailscale_connecting) to working
        TailscaleKind.SigningOut -> stringResource(Res.string.tailscale_signing_out) to working
        TailscaleKind.Removing -> stringResource(Res.string.tailscale_removing) to working
        TailscaleKind.WaitingForSignIn -> stringResource(Res.string.tailscale_waiting) to waiting?.let { url -> @Composable { button(Res.string.tailscale_open_link) { openLink(url) } } }
        TailscaleKind.Connected -> stringResource(Res.string.tailscale_connected, name) to @Composable { button(Res.string.tailscale_sign_out, onSignOut) }
        TailscaleKind.Failed -> (row.detail?.let { stringResource(Res.string.tailscale_failed_detail, it) } ?: stringResource(Res.string.tailscale_failed)) to
            @Composable { button(Res.string.tailscale_try_again) { onAct(LinuxAction.TailscaleRetry) } }
    }
    SettingsRow(
        icon = "tailscale",
        title = stringResource(Res.string.tailscale_title),
        subtitle = subtitle,
        shape = shape,
        loading = row.kind == TailscaleKind.Checking,
        // Set up but not signed in: the row itself offers to remove it, which signing out offers otherwise.
        onClick = if (row.kind == TailscaleKind.SignedOut) onRemove else null,
        trailing = trailing,
    )
}

/** Removes Tailscale when it's set up and not signed in. */
@Composable
private fun RemoveDialog(onDone: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(Res.string.tailscale_remove_title)) },
        text = { Text(stringResource(Res.string.tailscale_remove_body)) },
        confirmButton = {
            TextButton(onClick = { onDone(); onRemove() }) { Text(stringResource(Res.string.tailscale_remove), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(Res.string.computer_cancel)) } },
    )
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

/** Enough of a fingerprint to tell two computers apart; the full one is what `ssh-keygen -l` prints. */
private const val FINGERPRINT_CHARS = 19
private const val KEY_LINES = 3
