package app.whittle.companion

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import app.whittle.companion.resources.Res
import app.whittle.companion.resources.*
import app.whittle.connect.SecretField
import app.whittle.design.components.BeaverScene
import app.whittle.design.components.CopyableText
import app.whittle.design.components.NumberedStep
import app.whittle.design.components.SceneProgress
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.WhittleFilledField
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.plainTextClip
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The computer's first screen: the beaver scene with how the link stands, what this window is, and three numbered steps
 * with Connect under them. In a browser they're turn it on, open the tunnel, paste the token, the phone's address typed
 * once filling both commands; where a [tunnel] opens by itself (the Mac app), they're turn it on, add this computer,
 * and its address, and the phone it reached last is reached again at start. From [TWO_COLUMNS] wide, the scene and
 * the words sit beside the steps, and stay put while the steps scroll.
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
    val problem = tunnelProblem?.let { tunnelWords(it) } ?: (state as? LinkState.Off)?.problem?.let { PROBLEMS.getValue(it).let { (title, help) -> stringResource(title) to stringResource(help) } }
    val chrome = LocalWindowChrome.current
    Box(Modifier.fillMaxSize()) {
        // Where the app draws the window's top, it still moves the window.
        chrome?.let { window -> window.Draggable { Box(Modifier.fillMaxWidth().windowInsetsTopHeight(window.insets)) } }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(chrome?.insets ?: WindowInsets(0))) {
            val intro = @Composable { Intro(connecting, problem?.first) }
            val steps = @Composable {
                if (tunnel != null) {
                    TunnelSteps(tunnel, connecting, problem, host, { host = it; tunnelProblem = null }, openTunnel)
                } else {
                    Steps(state, connecting, problem, phone, host, { host = it }, secret, { secret = it.trim() }, connect)
                }
            }
            if (maxWidth >= TWO_COLUMNS) {
                Row(
                    Modifier.align(Alignment.TopCenter).widthIn(max = MAX_WIDTH).padding(horizontal = WhittleSpace.xxl),
                    horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
                ) {
                    Box(Modifier.weight(INTRO_WEIGHT).padding(vertical = WhittleSpace.xxl)) { intro() }
                    Box(Modifier.weight(STEPS_WEIGHT).verticalScroll(rememberScrollState()).padding(vertical = WhittleSpace.xxl)) { steps() }
                }
            } else {
                Column(
                    Modifier.align(Alignment.TopCenter).widthIn(max = ONE_COLUMN_MAX).verticalScroll(rememberScrollState()).padding(WhittleSpace.l),
                    verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl),
                ) {
                    intro()
                    steps()
                }
            }
        }
    }
}

/** The scene with how the link stands ([problem], when there is one), then what this window is and who may open it. */
@Composable
private fun Intro(connecting: Boolean, problem: String?) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl)) {
        BeaverScene(
            title = stringResource(Res.string.connect_scene_title),
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
        Column(Modifier.padding(horizontal = WhittleSpace.xs), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            Text(stringResource(Res.string.connect_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(Res.string.connect_body), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        SupportingNote("lock", stringResource(Res.string.connect_keeps))
    }
}

/** Turn it on, open the tunnel, paste the token; then Connect, what went wrong, and the link's address. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Steps(
    state: LinkState,
    connecting: Boolean,
    problem: Pair<String, String>?,
    phone: PhoneAddress,
    host: String,
    onHost: (String) -> Unit,
    secret: String,
    onSecret: (String) -> Unit,
    onConnect: () -> Unit,
) {
    val target = host.trim().ifEmpty { stringResource(Res.string.connect_host_placeholder) }
    Column {
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
            NumberedStep(1, stringResource(Res.string.connect_step_on), stringResource(Res.string.connect_step_on_detail), segmentedShape(0, STEPS)) {
                WhittleFilledField(host, onHost, stringResource(Res.string.connect_phone), singleLine = true)
            }
            NumberedStep(2, stringResource(Res.string.connect_step_tunnel), stringResource(Res.string.connect_step_tunnel_detail), segmentedShape(1, STEPS), done = host.isNotBlank()) {
                Command(phone.tunnel(target))
            }
            NumberedStep(3, stringResource(Res.string.connect_step_token), stringResource(Res.string.connect_step_token_detail), segmentedShape(2, STEPS), done = secret.isNotBlank()) {
                Command(phone.readToken(target))
                SecretField(secret, onSecret, stringResource(Res.string.connect_token), enabled = !connecting, isError = (state as? LinkState.Off)?.problem == LinkProblem.Refused, onDone = onConnect)
            }
        }
        ConnectButton(enabled = secret.isNotBlank(), connecting = connecting, onConnect = onConnect)
        problem?.let { Problem(it) }
        SupportingNote("link", stringResource(Res.string.connect_link, phone.webSocket), Modifier.padding(top = WhittleSpace.m))
    }
}

/**
 * Where the tunnel opens by itself: turn it on, add this computer's key on the phone (made in Terminal when it has
 * none), and the phone's address; Connect reads the token and opens the tunnel.
 */
@Composable
private fun TunnelSteps(
    tunnel: PhoneTunnel,
    connecting: Boolean,
    problem: Pair<String, String>?,
    host: String,
    onHost: (String) -> Unit,
    onConnect: () -> Unit,
) {
    // Read again when the user says they've made a key.
    var checks by remember { mutableStateOf(0) }
    val key = remember(checks) { tunnel.publicKey }
    Column {
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
            NumberedStep(1, stringResource(Res.string.connect_step_on), stringResource(Res.string.connect_step_on_detail), segmentedShape(0, STEPS))
            NumberedStep(2, stringResource(Res.string.connect_step_key), stringResource(Res.string.connect_step_key_detail), segmentedShape(1, STEPS)) {
                if (key != null) {
                    Command(key)
                } else {
                    Text(stringResource(Res.string.connect_no_key), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Command(tunnel.makeKeyCommand)
                    TextButton(onClick = { checks++ }) { Text(stringResource(Res.string.connect_check_again)) }
                }
            }
            NumberedStep(3, stringResource(Res.string.connect_step_address), stringResource(Res.string.connect_step_address_detail), segmentedShape(2, STEPS), done = host.isNotBlank()) {
                WhittleFilledField(host, onHost, stringResource(Res.string.connect_phone), singleLine = true, enabled = !connecting)
            }
        }
        ConnectButton(enabled = host.isNotBlank(), connecting = connecting, onConnect = onConnect)
        problem?.let { Problem(it) }
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
        modifier = Modifier.fillMaxWidth().padding(top = WhittleSpace.l).height(WhittleSize.Button.large),
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

/** What the tunnel's failure was, and what to check. */
@Composable
private fun tunnelWords(result: TunnelResult): Pair<String, String>? = when (result) {
    is TunnelResult.Opened -> null
    TunnelResult.NotAdded -> stringResource(Res.string.connect_not_added) to stringResource(Res.string.connect_not_added_help)
    TunnelResult.Unreachable -> stringResource(Res.string.connect_unreachable) to stringResource(Res.string.connect_unreachable_help)
    TunnelResult.HostChanged -> stringResource(Res.string.connect_host_changed) to stringResource(Res.string.connect_host_changed_help)
    TunnelResult.NoSsh -> stringResource(Res.string.connect_no_ssh) to stringResource(Res.string.connect_no_ssh_help)
    is TunnelResult.Failed -> stringResource(Res.string.connect_tunnel_failed) to result.detail.orEmpty()
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

/** What went wrong, and what to check. */
@Composable
private fun Problem(words: Pair<String, String>) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = WhittleSpace.l, start = WhittleSpace.s, end = WhittleSpace.s),
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        WhittleIcon("error", size = WhittleSize.Icon.m, tint = colors.error)
        Column {
            Text(words.first, style = MaterialTheme.typography.bodyMedium)
            if (words.second.isNotBlank()) Text(words.second, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** Each way the link can fail: what happened, and what to check. */
private val PROBLEMS = mapOf(
    LinkProblem.Unreachable to (Res.string.connect_failed to Res.string.connect_failed_help),
    LinkProblem.Refused to (Res.string.connect_refused to Res.string.connect_refused_help),
    LinkProblem.Lost to (Res.string.connect_lost to Res.string.connect_lost_help),
)

private const val STEPS = 3

/** Joins the characters either side of it, so no line breaks between them. */
private const val WORD_JOINER = '\u2060'

/** The slash a path starts with, or the dash an option does. */
private val WORD_START = Regex("(?<=^| )[/-]")

/** Wide enough for the scene and the steps side by side, each at a comfortable width. */
private val TWO_COLUMNS = 820.dp
private val MAX_WIDTH = 1040.dp
private val ONE_COLUMN_MAX = 560.dp
private val COLUMN_GAP = 48.dp
private const val INTRO_WEIGHT = 5f
private const val STEPS_WEIGHT = 6f
