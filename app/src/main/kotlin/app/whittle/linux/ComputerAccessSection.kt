package app.whittle.linux

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
 * Settings › Linux workspace › Your computer: the switch, and while it's on, how to sign in, the computers that may,
 * and Add a computer. Off, it's the switch alone; without Wi-Fi it says so and can't be turned on.
 */
@Composable
internal fun ComputerAccessSection(graph: AppGraph) {
    val access = graph.computerAccess
    val state by access.state.collectAsStateWithLifecycle()
    val address by access.address.collectAsStateWithLifecycle()
    val computers by access.computers.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { access.load() }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val on = state as? ComputerAccessState.On
    val busy = state == ComputerAccessState.Installing || state == ComputerAccessState.Starting
    // The switch, then while it's on: how to sign in, each computer, and Add a computer.
    val rows = if (on != null) 2 + computers.size + 1 else 1
    SettingsSection(
        stringResource(R.string.computer_title),
        footer = stringResource(if (on != null) R.string.computer_footer_on else R.string.computer_footer_off),
    ) {
        SettingsToggleRow(
            icon = "computer",
            title = stringResource(R.string.computer_switch),
            subtitle = when (val current = state) {
                ComputerAccessState.Installing -> stringResource(R.string.computer_installing)
                ComputerAccessState.Starting -> stringResource(R.string.computer_starting)
                is ComputerAccessState.On -> stringResource(R.string.computer_on, current.address)
                is ComputerAccessState.Failed -> current.detail?.let { stringResource(R.string.computer_failed_detail, it) } ?: stringResource(R.string.computer_failed)
                ComputerAccessState.Off -> if (address == null) stringResource(R.string.computer_needs_wifi) else null
            },
            checked = on != null || busy,
            onCheckedChange = { turnOn -> if (turnOn) access.turnOn() else access.turnOff() },
            shape = segmentedShape(0, rows),
            enabled = on != null || busy || address != null,
        )
        if (on != null) {
            val signIn = access.config.signIn(on.address)
            SettingsRow(
                icon = "terminal",
                title = signIn,
                subtitle = null,
                shape = segmentedShape(1, rows),
                trailing = {
                    IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(signIn, signIn))) } }) {
                        WhittleIcon("content_copy", size = WhittleSize.Icon.l, contentDescription = stringResource(R.string.computer_copy))
                    }
                },
            )
            computers.forEachIndexed { index, computer ->
                SettingsRow(
                    icon = "computer",
                    title = computer.comment.ifBlank { stringResource(R.string.computer_unnamed) },
                    subtitle = computer.fingerprint.take(FINGERPRINT_CHARS) + "…",
                    shape = segmentedShape(2 + index, rows),
                    trailing = { TextButton(onClick = { scope.launch { access.remove(computer) } }) { Text(stringResource(R.string.computer_remove)) } },
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
