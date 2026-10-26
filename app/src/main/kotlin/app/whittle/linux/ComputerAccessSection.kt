package app.whittle.linux

import android.content.ClipData
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.settings.CopyableText
import kotlinx.coroutines.launch

/**
 * Settings › Linux workspace › Your computer: the switch, Tailscale, and while it's on, how to sign in (on the same
 * Wi-Fi, and from anywhere over Tailscale), the computers that may, and Add a computer. It turns on with Wi-Fi, or
 * with Tailscale signed in.
 */
@Composable
internal fun ComputerAccessSection(graph: AppGraph) {
    val access = graph.computerAccess
    val tailscale = access.tailscale
    val state by access.state.collectAsStateWithLifecycle()
    val address by access.address.collectAsStateWithLifecycle()
    val computers by access.computers.collectAsStateWithLifecycle()
    val tailnet by tailscale.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        access.load()
        tailscale.check()
    }
    var adding by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val on = state as? ComputerAccessState.On
    val busy = state == ComputerAccessState.Installing || state == ComputerAccessState.Starting
    val reachable = address != null || tailscale.signedIn
    // The switch and Tailscale, then while it's on: how to sign in, each computer, and Add a computer.
    val signIns = listOfNotNull(on?.wifi, on?.tailscale)
    val rows = 2 + if (on != null) signIns.size + computers.size + 1 else 0
    SettingsSection(
        stringResource(R.string.computer_title),
        footer = stringResource(
            when {
                on?.tailscale != null -> R.string.computer_footer_tailscale
                on != null -> R.string.computer_footer_on
                else -> R.string.computer_footer_off
            },
        ),
    ) {
        SettingsToggleRow(
            icon = "computer",
            title = stringResource(R.string.computer_switch),
            subtitle = when (val current = state) {
                ComputerAccessState.Installing -> stringResource(R.string.computer_installing)
                ComputerAccessState.Starting -> stringResource(R.string.computer_starting)
                is ComputerAccessState.On -> when {
                    current.wifi != null && current.tailscale != null -> stringResource(R.string.computer_on_both)
                    current.wifi != null -> stringResource(R.string.computer_on, current.wifi)
                    else -> stringResource(R.string.computer_on_tailscale)
                }
                is ComputerAccessState.Failed -> current.detail?.let { stringResource(R.string.computer_failed_detail, it) } ?: stringResource(R.string.computer_failed)
                ComputerAccessState.Off -> if (!reachable) stringResource(R.string.computer_needs_network) else null
            },
            checked = on != null || busy,
            onCheckedChange = { turnOn -> if (turnOn) access.turnOn() else access.turnOff() },
            shape = segmentedShape(0, rows),
            enabled = on != null || busy || reachable,
        )
        TailscaleRow(
            state = tailnet,
            shape = segmentedShape(1, rows),
            onSetUp = tailscale::install,
            onSignIn = access::signInToTailscale,
            onSignOut = { signingOut = true },
            onRemove = { removing = true },
            onRetry = { graph.appScope.launch { tailscale.check() } },
        )
        if (on != null) {
            on.wifi?.let { SignInRow(access.config.signIn(it), stringResource(R.string.computer_same_wifi), segmentedShape(2, rows)) }
            on.tailscale?.let { SignInRow(access.config.signIn(it), stringResource(R.string.computer_anywhere), segmentedShape(2 + signIns.size - 1, rows)) }
            computers.forEachIndexed { index, computer ->
                SettingsRow(
                    icon = "computer",
                    title = computer.comment.ifBlank { stringResource(R.string.computer_unnamed) },
                    subtitle = computer.fingerprint.take(FINGERPRINT_CHARS) + "…",
                    shape = segmentedShape(2 + signIns.size + index, rows),
                    trailing = { TextButton(onClick = { graph.appScope.launch { access.remove(computer) } }) { Text(stringResource(R.string.computer_remove)) } },
                )
            }
            SettingsRow(
                icon = "add",
                title = stringResource(R.string.computer_add),
                subtitle = null,
                shape = segmentedShape(rows - 1, rows),
                onClick = { adding = true },
            )
        }
    }
    if (adding) AddComputerSheet(access, onDone = { adding = false })
    if (signingOut) SignOutDialog(onDone = { signingOut = false }) { remove -> tailscale.signOut(remove) }
    if (removing) RemoveDialog(onDone = { removing = false }) { tailscale.signOut(remove = true) }
}

/** What a computer types to sign in, with where it works and a copy button. */
@Composable
private fun SignInRow(command: String, where: String, shape: androidx.compose.ui.graphics.Shape) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    SettingsRow(
        icon = "terminal",
        title = command,
        subtitle = where,
        shape = shape,
        trailing = {
            IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(command, command))) } }) {
                WhittleIcon("content_copy", size = WhittleSize.Icon.l, contentDescription = stringResource(R.string.computer_copy))
            }
        },
    )
}

/**
 * Tailscale's row: set it up, sign in (the link opens in the browser by itself, and again from Open link), and once
 * connected, the phone's name on the tailnet with Sign out.
 */
@Composable
private fun TailscaleRow(
    state: TailscaleState,
    shape: androidx.compose.ui.graphics.Shape,
    onSetUp: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val openLink = { url: String ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No browser; the row still offers the link.
        }
    }
    // Each new link opens once by itself; Open link opens it again.
    val waiting = (state as? TailscaleState.WaitingForSignIn)?.url
    LaunchedEffect(waiting) { waiting?.let(openLink) }
    val button = @Composable { label: Int, onClick: () -> Unit -> TextButton(onClick = onClick) { Text(stringResource(label)) } }
    // While it works, a spinner where its button goes, as the Version row does while updating.
    val working = @Composable { CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline) }
    val (subtitle, trailing) = when (state) {
        TailscaleState.Checking -> null to null
        TailscaleState.NotInstalled -> stringResource(R.string.tailscale_pitch) to @Composable { button(R.string.tailscale_set_up, onSetUp) }
        TailscaleState.Installing -> stringResource(R.string.tailscale_setting_up) to working
        TailscaleState.SignedOut -> stringResource(R.string.tailscale_signed_out) to @Composable { button(R.string.tailscale_sign_in, onSignIn) }
        is TailscaleState.SignedIn -> stringResource(R.string.tailscale_signed_in, state.name) to @Composable { button(R.string.tailscale_sign_out, onSignOut) }
        TailscaleState.Connecting -> stringResource(R.string.tailscale_connecting) to working
        TailscaleState.SigningOut -> stringResource(R.string.tailscale_signing_out) to working
        TailscaleState.Removing -> stringResource(R.string.tailscale_removing) to working
        is TailscaleState.WaitingForSignIn -> stringResource(R.string.tailscale_waiting) to @Composable { button(R.string.tailscale_open_link) { openLink(state.url) } }
        is TailscaleState.Connected -> stringResource(R.string.tailscale_connected, state.name) to @Composable { button(R.string.tailscale_sign_out, onSignOut) }
        is TailscaleState.Failed -> (state.detail?.let { stringResource(R.string.tailscale_failed_detail, it) } ?: stringResource(R.string.tailscale_failed)) to
            @Composable { button(R.string.tailscale_try_again, onRetry) }
    }
    SettingsRow(
        icon = "tailscale",
        title = stringResource(R.string.tailscale_title),
        subtitle = subtitle,
        shape = shape,
        loading = state == TailscaleState.Checking,
        // Set up but not signed in: the row itself offers to remove it, which signing out offers otherwise.
        onClick = if (state == TailscaleState.SignedOut) onRemove else null,
        trailing = trailing,
    )
}

/** Removes Tailscale when it's set up and not signed in. */
@Composable
private fun RemoveDialog(onDone: () -> Unit, onRemove: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.tailscale_remove_title)) },
        text = { Text(stringResource(R.string.tailscale_remove_body)) },
        confirmButton = {
            TextButton(onClick = { onDone(); onRemove() }) { Text(stringResource(R.string.tailscale_remove), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.computer_cancel)) } },
    )
}

/** Sign out, and if the user asks, remove Tailscale too. */
@Composable
private fun SignOutDialog(onDone: () -> Unit, onSignOut: (remove: Boolean) -> Unit) {
    var remove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.tailscale_sign_out_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                Text(stringResource(R.string.tailscale_sign_out_body))
                Row(
                    Modifier.fillMaxWidth().toggleable(value = remove, role = Role.Checkbox) { remove = it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
                ) {
                    Checkbox(checked = remove, onCheckedChange = null)
                    Text(stringResource(R.string.tailscale_remove_too))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(); onSignOut(remove) }) { Text(stringResource(R.string.tailscale_sign_out), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.computer_cancel)) } },
    )
}

/** Paste a computer's public key: the command that prints it, the field, and why a key wasn't taken. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddComputerSheet(access: ComputerAccess, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var key by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<KeyProblem?>(null) }
    var saving by remember { mutableStateOf(false) }
    val printKey = access.config.printKeyCommand

    fun add() {
        saving = true
        scope.launch {
            problem = access.add(key)
            saving = false
            if (problem == null) onDone()
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
                    Text(stringResource(R.string.computer_sheet_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.computer_sheet_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                CopyableText(
                    text = printKey,
                    copyDescription = stringResource(R.string.computer_copy),
                    onCopy = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(printKey, printKey))) } },
                    code = true,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; problem = null },
                    label = { Text(stringResource(R.string.computer_key_label)) },
                    textStyle = TextStyle(fontFamily = WhittleFonts.mono),
                    minLines = KEY_LINES,
                    isError = problem != null,
                    supportingText = problem?.let { { Text(problemText(it, access.config.maxComputers)) } },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.computer_no_key, access.config.makeKeyCommand), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = WhittleSpace.xxl, vertical = WhittleSpace.m),
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDone, enabled = !saving) { Text(stringResource(R.string.computer_cancel)) }
                Button(onClick = ::add, enabled = key.isNotBlank() && !saving, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.computer_add_action))
                }
            }
        }
    }
}

@Composable
private fun problemText(problem: KeyProblem, max: Int): String = when (problem) {
    KeyProblem.NOT_A_KEY -> stringResource(R.string.computer_error_not_a_key)
    KeyProblem.TYPE_NOT_ALLOWED -> stringResource(R.string.computer_error_type)
    KeyProblem.ALREADY_ADDED -> stringResource(R.string.computer_error_added)
    KeyProblem.TOO_MANY -> pluralStringResource(R.plurals.computer_error_too_many, max, max)
}

/** Enough of a fingerprint to tell two computers apart; the full one is what `ssh-keygen -l` prints. */
private const val FINGERPRINT_CHARS = 19
private const val KEY_LINES = 3
