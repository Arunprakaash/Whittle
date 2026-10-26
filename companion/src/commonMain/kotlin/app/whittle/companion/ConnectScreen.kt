package app.whittle.companion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.whittle.companion.resources.Res
import app.whittle.companion.resources.*
import app.whittle.connect.SecretField
import app.whittle.design.components.BeaverScene
import app.whittle.design.components.CopyableText
import app.whittle.design.components.NumberedStep
import app.whittle.design.components.SceneProgress
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleMotion
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.plainTextClip
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The computer's first screen, one column in the middle of the window: the beaver scene, titled Connect to your phone,
 * with how the link stands, then what to do. Where a [tunnel] opens by itself (the Mac app) that's the phone's address
 * and Connect, with setting up a phone folded under them once one has connected; the phone it reached last is reached
 * again at start. In a browser it's three numbered steps: turn it on, open the tunnel, paste the token.
 */
@Composable
internal fun ConnectScreen(state: LinkState, phone: PhoneAddress, token: String, tunnel: PhoneTunnel?, onConnect: (String, String) -> Unit) {
    var host by rememberSaveable { mutableStateOf(tunnel?.lastHost.orEmpty()) }
    var secret by rememberSaveable { mutableStateOf(token) }
    var opening by remember { mutableStateOf(false) }
    var tunnelProblem by remember { mutableStateOf<TunnelResult?>(null) }
    val scope = rememberCoroutineScope()
    val connecting = state == LinkState.Connecting || opening
    val connect = { if (secret.isNotBlank() && !connecting) onConnect(phone.webSocket, secret.trim()) }
    val openTunnel = {
        if (tunnel != null && host.isNotBlank() && !connecting) {
            opening = true
            tunnelProblem = null
            scope.launch {
                when (val opened = tunnel.open(host)) {
                    is TunnelResult.Opened -> onConnect(opened.address, opened.token)
                    else -> tunnelProblem = opened
                }
                opening = false
            }
        }
    }
    if (tunnel?.lastHost != null) LaunchedEffect(Unit) { if ((state as? LinkState.Off)?.problem == null) openTunnel() }
    val linkProblem = (state as? LinkState.Off)?.problem
    val problem = tunnelProblem?.let { tunnelWords(it) } ?: linkProblem?.let { linkWords(it, tunnel != null) }
    val chrome = LocalWindowChrome.current
    Box(Modifier.fillMaxSize()) {
        // Where the app draws the window's top, it still moves the window.
        chrome?.let { window -> window.Draggable { Box(Modifier.fillMaxWidth().windowInsetsTopHeight(window.insets)) } }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(chrome?.insets ?: WindowInsets(0))) {
            // In the middle while it fits; scrolls when the window is shorter than the column.
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(WhittleSpace.l),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = COLUMN_MAX), verticalArrangement = Arrangement.spacedBy(WhittleSpace.l)) {
                    Scene(connecting, problem?.first)
                    if (tunnel != null) {
                        val notAdded = tunnelProblem == TunnelResult.NotAdded
                        Address(host, { host = it; tunnelProblem = null }, problem?.second, connecting, openTunnel)
                        ConnectButton(enabled = host.isNotBlank(), connecting = connecting, onConnect = openTunnel)
                        Setup(tunnel, firstTime = tunnel.lastHost == null, notAdded = notAdded)
                    } else {
                        Steps(linkProblem, connecting, phone, host, { host = it }, secret, { secret = it.trim() }, connect)
                        ConnectButton(enabled = secret.isNotBlank(), connecting = connecting, onConnect = connect)
                        problem?.second?.let { Problem(it) }
                    }
                }
            }
        }
    }
}

/** The beaver scene, titled with what this screen is, and how the link stands: [problem] when there is one. */
@Composable
private fun Scene(connecting: Boolean, problem: String?) {
    BeaverScene(
        title = stringResource(Res.string.connect_title),
        status = when {
            connecting -> stringResource(Res.string.connect_connecting)
            problem != null -> problem
            else -> stringResource(Res.string.connect_not_connected)
        },
        working = connecting,
        problem = problem != null && !connecting,
        progress = if (connecting) SceneProgress.Running else SceneProgress.None,
        modifier = Modifier.clip(RoundedCornerShape(WhittleRadius.xl)),
    )
}

/**
 * The phone's address: one line with the Wi-Fi mark, as the key field is drawn, and under it what to check when
 * connecting failed ([error]). Return connects.
 */
@Composable
private fun Address(host: String, onHost: (String) -> Unit, error: String?, connecting: Boolean, onConnect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge = when {
        error != null -> colors.error
        focused -> colors.outline
        else -> Color.Transparent
    }
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        BasicTextField(
            value = host,
            onValueChange = onHost,
            enabled = !connecting,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = WhittleFonts.mono, color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onConnect() }),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { input ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(WhittleSize.Button.default)
                        .clip(RoundedCornerShape(WhittleRadius.l))
                        .background(colors.surfaceContainerLowest)
                        .border(WhittleSpace.hairline, edge, RoundedCornerShape(WhittleRadius.l))
                        .padding(horizontal = WhittleSpace.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
                ) {
                    WhittleIcon("wifi", size = WhittleSize.Icon.l, tint = colors.onSurfaceVariant)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (host.isEmpty()) Text(stringResource(Res.string.connect_phone), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant, maxLines = 1)
                        input()
                    }
                }
            },
        )
        error?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.padding(horizontal = WhittleSpace.m))
        }
    }
}

/**
 * Setting up a phone: turn it on, add this computer's key. Open the first time and when the phone didn't know this
 * computer ([notAdded]); otherwise folded into one line under Connect.
 */
@Composable
private fun Setup(tunnel: PhoneTunnel, firstTime: Boolean, notAdded: Boolean) {
    var open by rememberSaveable { mutableStateOf(firstTime) }
    LaunchedEffect(notAdded) { if (notAdded) open = true }
    // Read again when the user says they've made a key.
    var checks by remember { mutableStateOf(0) }
    val key = remember(checks) { tunnel.publicKey }
    Column(Modifier.padding(top = WhittleSpace.m), verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        Fold(stringResource(if (firstTime) Res.string.connect_setup_first else Res.string.connect_setup_another), open) { open = !open }
        if (open) {
            NumberedStep(1, stringResource(Res.string.connect_step_on), stringResource(Res.string.connect_step_on_detail), segmentedShape(0, SETUP_STEPS))
            if (key != null) {
                // The key's type and its last characters, in the code font: enough to tell it's this computer's. Copy
                // takes all of it.
                val glimpse = keyGlimpse(key)
                val words = stringResource(Res.string.connect_step_key_detail, glimpse)
                val code = MaterialTheme.colorScheme.onSurface
                val detail = remember(words, glimpse, code) {
                    buildAnnotatedString {
                        append(words)
                        val start = words.indexOf(glimpse)
                        if (start >= 0) addStyle(SpanStyle(fontFamily = WhittleFonts.mono, color = code), start, start + glimpse.length)
                    }
                }
                NumberedStep(2, stringResource(Res.string.connect_step_key), detail, segmentedShape(1, SETUP_STEPS), trailing = { CopyKey(key) })
            } else {
                NumberedStep(2, stringResource(Res.string.connect_step_key), stringResource(Res.string.connect_step_key_detail_no_key), segmentedShape(1, SETUP_STEPS)) {
                    Text(stringResource(Res.string.connect_no_key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Command(tunnel.makeKeyCommand)
                    TextButton(onClick = { checks++ }) { Text(stringResource(Res.string.connect_check_again)) }
                }
            }
        }
    }
}

/** A quiet line that opens and folds what's under it, its chevron turning as it does. */
@Composable
private fun Fold(label: String, open: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val chevron by animateFloatAsState(if (open) HALF_TURN else 0f, WhittleMotion.spatialDefault.spec(), label = "setupChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WhittleRadius.m))
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onToggle() }
            .padding(horizontal = WhittleSpace.s, vertical = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        WhittleIcon("expand_more", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(chevron))
    }
}

/** Turn it on, open the tunnel, paste the token: a browser opens the tunnel by hand, the phone's address filling both commands. */
@Composable
private fun Steps(
    problem: LinkProblem?,
    connecting: Boolean,
    phone: PhoneAddress,
    host: String,
    onHost: (String) -> Unit,
    secret: String,
    onSecret: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val target = host.trim().ifEmpty { stringResource(Res.string.connect_host_placeholder) }
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        NumberedStep(1, stringResource(Res.string.connect_step_on), stringResource(Res.string.connect_step_on_detail), segmentedShape(0, STEPS)) {
            Address(host, onHost, error = null, connecting = connecting, onConnect = {})
        }
        NumberedStep(2, stringResource(Res.string.connect_step_tunnel), stringResource(Res.string.connect_step_tunnel_detail), segmentedShape(1, STEPS), done = host.isNotBlank()) {
            Command(phone.tunnel(target))
        }
        NumberedStep(3, stringResource(Res.string.connect_step_token), stringResource(Res.string.connect_step_token_detail), segmentedShape(2, STEPS), done = secret.isNotBlank()) {
            Command(phone.readToken(target))
            SecretField(secret, onSecret, stringResource(Res.string.connect_token), enabled = !connecting, isError = problem == LinkProblem.Refused, onDone = onConnect)
        }
    }
}

/** Connect, or a spinner and Connecting… while it does. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectButton(enabled: Boolean, connecting: Boolean, onConnect: () -> Unit) {
    val haptics = WhittleTheme.haptics
    Button(
        onClick = { haptics.perform(WhittleHaptic.Tap); onConnect() },
        enabled = enabled && !connecting,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.large),
    ) {
        if (connecting) {
            CircularProgressIndicator(Modifier.size(WhittleSize.Icon.l), strokeWidth = WhittleSpace.hairline, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            WhittleIcon("computer", size = WhittleSize.Icon.l)
        }
        Spacer(Modifier.width(WhittleSpace.s))
        Text(stringResource(if (connecting) Res.string.connect_connecting else Res.string.connect_go), style = MaterialTheme.typography.titleMedium)
    }
}

/** What the tunnel's failure was, for the scene's status, and what to check. */
@Composable
private fun tunnelWords(result: TunnelResult): Pair<String, String>? = when (result) {
    is TunnelResult.Opened -> null
    TunnelResult.NotAdded -> stringResource(Res.string.connect_not_added) to stringResource(Res.string.connect_not_added_help)
    TunnelResult.Unreachable -> stringResource(Res.string.connect_unreachable) to stringResource(Res.string.connect_unreachable_help)
    TunnelResult.HostChanged -> stringResource(Res.string.connect_host_changed) to stringResource(Res.string.connect_host_changed_help)
    TunnelResult.NoSsh -> stringResource(Res.string.connect_no_ssh) to stringResource(Res.string.connect_no_ssh_help)
    is TunnelResult.Failed -> stringResource(Res.string.connect_tunnel_failed) to result.detail.orEmpty()
}

/** How the link failed, and what to check: with a [tunnel] of its own the app opens it again on Connect. */
@Composable
private fun linkWords(problem: LinkProblem, tunnel: Boolean): Pair<String, String> {
    val (title, help) = PROBLEMS.getValue(problem)
    val tunnelHelp = if (problem == LinkProblem.Lost) Res.string.connect_lost_again_help else Res.string.connect_unreachable_help
    return stringResource(title) to stringResource(if (tunnel) tunnelHelp else help)
}

/** A round button that copies this computer's [key], ticking for a moment once it has. */
@Composable
private fun CopyKey(key: String) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var copied by remember { mutableStateOf(false) }
    if (copied) LaunchedEffect(Unit) { delay(COPIED_MILLIS); copied = false }
    FilledIconButton(
        onClick = {
            haptics.perform(WhittleHaptic.Tap)
            scope.launch { clipboard.setClipEntry(plainTextClip(key, key)) }
            copied = true
        },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.surfaceContainerHigh, contentColor = colors.onSurfaceVariant),
    ) {
        WhittleIcon(
            if (copied) "check" else "content_copy",
            size = WhittleSize.Icon.m,
            contentDescription = stringResource(if (copied) Res.string.connect_copied else Res.string.connect_copy_key),
        )
    }
}

/** A command to run on this computer, with a copy button. */
@Composable
private fun Command(command: String) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    CopyableText(
        // A path or an option never breaks right after its first slash or dash. Copy takes the command as it is.
        text = command.replace(WORD_START) { "${it.value}$WORD_JOINER" },
        copyDescription = stringResource(Res.string.connect_copy),
        onCopy = { scope.launch { clipboard.setClipEntry(plainTextClip(command, command)) } },
        code = true,
    )
}

/** What to check, in the error colour, under Connect. */
@Composable
private fun Problem(help: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = WhittleSpace.s), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        WhittleIcon("error", size = WhittleSize.Icon.m, tint = colors.error)
        Text(help, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** A public key as `ed25519 …R3NezK`: its type without `ssh-`, and the end of its body. */
internal fun keyGlimpse(key: String): String {
    val parts = key.trim().split(' ')
    val body = parts.getOrNull(1) ?: return key
    return "${parts[0].removePrefix(KEY_TYPE_PREFIX)} $ELLIPSIS${body.takeLast(KEY_TAIL)}"
}

/** Each way the link can fail: what happened, and what to check. */
private val PROBLEMS: Map<LinkProblem, Pair<StringResource, StringResource>> = mapOf(
    LinkProblem.Unreachable to (Res.string.connect_failed to Res.string.connect_failed_help),
    LinkProblem.Refused to (Res.string.connect_refused to Res.string.connect_refused_help),
    LinkProblem.Lost to (Res.string.connect_lost to Res.string.connect_lost_help),
)

private const val STEPS = 3
private const val SETUP_STEPS = 2
private const val HALF_TURN = 180f
private const val COPIED_MILLIS = 1600L

private const val KEY_TYPE_PREFIX = "ssh-"
private const val KEY_TAIL = 6
private const val ELLIPSIS = '…'

/** Joins the characters either side of it, so no line breaks between them. */
private const val WORD_JOINER = '⁠'

/** The slash a path starts with, or the dash an option does. */
private val WORD_START = Regex("(?<=^| )[/-]")

/** As wide as the column gets: the scene's drawing and a comfortable field. */
private val COLUMN_MAX = 440.dp
