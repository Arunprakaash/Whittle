package app.whittle.claudecode

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.R
import app.whittle.design.components.BottomActionButton
import app.whittle.design.components.ScreenHeading
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.llm.api.ModelDescriptor
import app.whittle.tools.workspace.InteractiveCommand
import kotlinx.coroutines.launch

/**
 * Before Claude Code is used: what happens and whose terms apply, then it's installed from Anthropic and its own
 * sign-in opens. [onSignIn] opens the sign-in; [onReady] continues once it's installed and signed in.
 */
@Composable
fun ClaudeCodeSetupScreen(claudeCode: ClaudeCode, onBack: () -> Unit, onSignIn: () -> Unit, onReady: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by claudeCode.state.collectAsStateWithLifecycle()
    // After Install and sign in, the sign-in follows the install by itself.
    var installing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { claudeCode.refresh() }
    LaunchedEffect(state) {
        val ready = state as? ClaudeCodeState.Ready ?: return@LaunchedEffect
        if (!installing) return@LaunchedEffect
        installing = false
        if (ready.signedIn) onReady() else onSignIn()
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s)) {
                TopBarButton("arrow_back", stringResource(R.string.claude_code_back), onBack)
            }
        },
        bottomBar = {
            when (val current = state) {
                ClaudeCodeState.Checking, ClaudeCodeState.Installing -> Busy(
                    stringResource(if (current == ClaudeCodeState.Installing) R.string.claude_code_installing else R.string.claude_code_checking),
                )
                ClaudeCodeState.NoLinux -> Unit
                ClaudeCodeState.NotInstalled, is ClaudeCodeState.Failed -> BottomActionButton("download", stringResource(R.string.claude_code_install_action), {
                    installing = true
                    claudeCode.install()
                })
                is ClaudeCodeState.Ready -> if (current.signedIn) {
                    BottomActionButton("arrow_forward", stringResource(R.string.claude_code_continue), onReady)
                } else {
                    BottomActionButton("login", stringResource(R.string.claude_code_sign_in_action), onSignIn)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = WhittleSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl),
        ) {
            ScreenHeading(stringResource(R.string.claude_code_setup_title), stringResource(R.string.claude_code_setup_body))
            val steps = listOf(
                stringResource(R.string.claude_code_step_install) to stringResource(R.string.claude_code_step_install_detail),
                stringResource(R.string.claude_code_step_sign_in) to stringResource(R.string.claude_code_step_sign_in_detail),
            )
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                steps.forEachIndexed { index, (title, detail) -> NumberedRow(index + 1, title, detail, segmentedShape(index, steps.size)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                SupportingNote("account_balance_wallet", stringResource(R.string.claude_code_term_plan))
                SupportingNote("lock", stringResource(R.string.claude_code_term_sign_in))
                SupportingNote("swap_horiz", stringResource(R.string.claude_code_term_switch))
            }
            when (val current = state) {
                ClaudeCodeState.NoLinux -> Problem(stringResource(R.string.claude_code_no_linux))
                is ClaudeCodeState.Failed -> Problem(stringResource(R.string.claude_code_failed, current.detail))
                else -> Unit
            }
        }
    }
}

/**
 * Claude Code's own sign-in, shown as it prints it: its link opens Claude's sign-in in the browser, and a code the
 * browser shows is pasted back to it. Whittle only passes the words along and never reads the sign-in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClaudeCodeSignInScreen(claudeCode: ClaudeCode, onClose: () -> Unit, onSignedIn: () -> Unit) {
    BackHandler(onBack = onClose)
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var command by remember { mutableStateOf<InteractiveCommand?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var code by remember { mutableStateOf("") }
    LaunchedEffect(attempt) {
        lines.clear()
        failed = false
        val started = claudeCode.signIn()
        if (started == null) {
            failed = true
            return@LaunchedEffect
        }
        command = started
        try {
            started.lines.collect { line -> SignInOutput.clean(line)?.let(lines::add) }
            started.exitCode()
        } finally {
            started.stop()
            command = null
        }
        claudeCode.refresh().join()
        if ((claudeCode.state.value as? ClaudeCodeState.Ready)?.signedIn == true) onSignedIn() else failed = true
    }
    val link = lines.firstNotNullOfOrNull(SignInOutput::link)
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    Scaffold(
        containerColor = colors.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("close", stringResource(R.string.claude_code_back), onClose)
                Column {
                    Text(stringResource(R.string.claude_code_sign_in_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.claude_code_sign_in_subtitle), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().navigationBarsPadding().padding(WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
        ) {
            // Claude Code's words, as it prints them.
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(WhittleRadius.lIncreased))
                    .background(colors.inverseSurface),
                contentPadding = PaddingValues(WhittleSpace.l),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
            ) {
                items(lines) { line ->
                    Text(line, fontFamily = WhittleFonts.mono, style = MaterialTheme.typography.bodySmall, color = colors.inverseOnSurface)
                }
                if (command != null && lines.isEmpty()) {
                    item { CircularProgressIndicator(Modifier.size(WhittleSize.Icon.l), strokeWidth = WhittleSpace.hairline, color = colors.inverseOnSurface) }
                }
            }
            if (failed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.claude_code_sign_in_failed), style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.claude_code_try_again)) }
                }
            }
            // The code in a pill with paste; beside it Open link, which turns into Send once there's a code.
            val send = {
                val typed = code.trim()
                if (typed.isNotEmpty() && command != null) {
                    haptics.perform(WhittleHaptic.Tap)
                    scope.launch { command?.write(typed) }
                    code = ""
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                CodeField(value = code, onValueChange = { code = it }, enabled = command != null, onSend = send, modifier = Modifier.weight(1f))
                if (code.isNotBlank()) {
                    Button(
                        onClick = send,
                        enabled = command != null,
                        shapes = ButtonDefaults.shapes(),
                        modifier = Modifier.height(WhittleSize.Button.default),
                    ) { Text(stringResource(R.string.claude_code_send_code)) }
                } else {
                    link?.let { url ->
                        FilledTonalButton(
                            onClick = {
                                haptics.perform(WhittleHaptic.Tap)
                                try {
                                    uriHandler.openUri(url)
                                } catch (exception: ActivityNotFoundException) {
                                    // No browser; the link stays on screen to copy.
                                }
                            },
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.height(WhittleSize.Button.default),
                        ) { Text(stringResource(R.string.claude_code_open_link)) }
                    }
                }
            }
        }
    }
}

/**
 * Picking a model when Claude Code is in use: the models it offers, and its own sign-out. Use another goes back to
 * the providers, as for an API key.
 */
@Composable
fun ClaudeCodePickerScreen(
    claudeCode: ClaudeCode,
    current: ModelDescriptor?,
    onChoose: (ModelDescriptor) -> Unit,
    onSignIn: () -> Unit,
    onUseAnother: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val colors = MaterialTheme.colorScheme
    val state by claudeCode.state.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { claudeCode.refresh() }
    val signedIn = (state as? ClaudeCodeState.Ready)?.signedIn == true
    Scaffold(
        containerColor = colors.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("arrow_back", stringResource(R.string.claude_code_back), onClose)
                Text(stringResource(R.string.connect_choose_model), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            item {
                Row(Modifier.padding(start = WhittleSpace.s, bottom = WhittleSpace.m), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(claudeCode.config.displayName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(if (signedIn) R.string.claude_code_signed_in else R.string.claude_code_signed_out),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onUseAnother) { Text(stringResource(R.string.claude_code_use_another)) }
                }
            }
            val models = claudeCode.models
            models.forEachIndexed { index, model ->
                item(key = model.id.value) {
                    PickRow(
                        title = model.displayName.substringAfter(" · "),
                        detail = claudeCode.detailOf(model),
                        trailing = if (model.id == current?.id) "check" else null,
                        shape = segmentedShape(index, models.size),
                        selected = model.id == current?.id,
                    ) { onChoose(model) }
                }
            }
            item { Spacer(Modifier.height(WhittleSpace.xl)) }
            val actions = if (signedIn) {
                listOf(
                    Triple("logout", R.string.claude_code_sign_out to R.string.claude_code_sign_out_detail) { claudeCode.signOut(); Unit },
                    Triple("delete", R.string.claude_code_remove to R.string.claude_code_remove_detail) { removing = true },
                )
            } else {
                listOf(
                    Triple("login", R.string.claude_code_sign_in_action to R.string.claude_code_step_sign_in_detail, onSignIn),
                    Triple("delete", R.string.claude_code_remove to R.string.claude_code_remove_detail) { removing = true },
                )
            }
            actions.forEachIndexed { index, (icon, words, action) ->
                item {
                    PickRow(
                        title = stringResource(words.first),
                        detail = stringResource(words.second),
                        leading = icon,
                        shape = segmentedShape(index, actions.size),
                        onClick = action,
                    )
                }
            }
        }
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.claude_code_remove_title)) },
            text = { Text(stringResource(R.string.claude_code_remove_body)) },
            confirmButton = {
                TextButton(onClick = { removing = false; claudeCode.remove(); onUseAnother() }) { Text(stringResource(R.string.claude_code_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.claude_code_cancel)) } },
        )
    }
}

@Composable
private fun PickRow(
    title: String,
    detail: String,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    leading: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onClick() }
            .padding(start = WhittleSpace.m, top = WhittleSpace.m, end = WhittleSpace.l, bottom = WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        leading?.let { icon ->
            Box(Modifier.size(WhittleSize.Tile.m).background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.m)), contentAlignment = Alignment.Center) {
                WhittleIcon(icon, size = WhittleSize.Icon.l, tint = colors.onSecondaryContainer)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        trailing?.let { WhittleIcon(it, size = WhittleSize.Icon.l, tint = colors.primary) }
    }
}

/**
 * The code the browser shows, in a pill: the paste icon fills it from the clipboard in one tap, and the keyboard's
 * send hands it to Claude Code, as the Send beside it does.
 */
@Composable
private fun CodeField(value: String, onValueChange: (String) -> Unit, enabled: Boolean, onSend: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.claude_code_paste_code)
    Row(
        modifier
            .height(WhittleSize.Button.default)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .padding(start = WhittleSpace.xs, end = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(WhittleSize.Tile.m)
                .clip(CircleShape)
                .clickable(enabled = enabled, role = Role.Button) {
                    scope.launch {
                        clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let(onValueChange)
                    }
                }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            WhittleIcon("content_paste", size = WhittleSize.Icon.l, tint = colors.onSurfaceVariant)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface, fontFamily = WhittleFonts.mono),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun NumberedRow(number: Int, title: String, detail: String, shape: RoundedCornerShape) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerLow).padding(WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(Modifier.size(WhittleSize.Tile.s).background(colors.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onSecondaryContainer)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Busy(label: String) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(WhittleSpace.xl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(WhittleSize.Icon.l), strokeWidth = WhittleSpace.hairline)
        Spacer(Modifier.width(WhittleSpace.m))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Problem(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

/** Claude Code's sign-in output, cleaned of terminal colour codes, and the link it prints. */
internal object SignInOutput {
    private val escapes = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007")
    private val url = Regex("https://\\S+")

    /** The line as words to show; null for a line that's only terminal control codes. */
    fun clean(line: String): String? = line.replace(escapes, "").replace("\r", "").trimEnd().takeIf { it.isNotBlank() || line.isEmpty() }

    fun link(line: String): String? = url.find(line)?.value
}
