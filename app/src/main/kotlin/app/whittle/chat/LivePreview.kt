package app.whittle.chat

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.agent.PastedKind
import app.whittle.R
import app.whittle.design.components.TopBarAction
import app.whittle.design.components.TopBarActionGroup
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.TopBarGroupAction
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.connectedShape
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** One line the page logged. */
private data class ConsoleLine(val level: ConsoleMessage.MessageLevel, val text: String) {
    val error: Boolean get() = level == ConsoleMessage.MessageLevel.ERROR
}

/**
 * What a process serves, live in a sheet over the chat: the page at its local address, its console one tap away
 * (Ask to fix hands the errors to Whittle), and Restart when the server isn't running.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LivePreviewSheet(item: ThreadItem.Process, viewModel: ChatViewModel, heightFraction: Float, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val processes by viewModel.processes.collectAsStateWithLifecycle()
    val info = processes.firstOrNull { it.id == item.processId }
    val url = info?.url ?: item.port?.let { "http://127.0.0.1:$it/" }
    val live = info?.live == true
    var reloads by remember { mutableIntStateOf(0) }
    var consoleOpen by remember { mutableStateOf(false) }
    val console = remember { mutableStateListOf<ConsoleLine>() }
    val errors = console.count { it.error }
    val colors = MaterialTheme.colorScheme
    val noBrowser = stringResource(R.string.preview_no_browser)
    val fixRequest = stringResource(R.string.preview_fix_message, item.name)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(heightFraction)) {
            Row(
                Modifier.fillMaxWidth().padding(start = WhittleSpace.l, end = WhittleSpace.l, bottom = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The name alone: the actions show whether it runs, and Open in browser has the address.
                Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // While it runs, its actions are one connected group; stopped, the Restart card below has the only one.
                if (live) {
                    val actions = listOfNotNull(
                        PreviewAction("refresh", R.string.preview_reload) { reloads++ },
                        url?.let { address ->
                            PreviewAction("open_in_new", R.string.preview_open_browser) {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } catch (exception: ActivityNotFoundException) {
                                    Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        PreviewAction("stop", R.string.preview_stop) { viewModel.stopProcess(item) },
                    )
                    TopBarActionGroup(Modifier.padding(end = WhittleSpace.s)) {
                        actions.forEachIndexed { index, action ->
                            TopBarAction(action.icon, stringResource(action.label), action.onClick, shape = connectedShape(index, actions.size), modifier = TopBarGroupAction)
                        }
                    }
                }
                TopBarButton("close", stringResource(R.string.preview_close), onClick = onDismiss)
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = WhittleSpace.s)) {
                if (url != null) {
                    PreviewPage(url, reloads, live, onConsole = { line -> console.add(line); if (console.size > MAX_LINES) console.removeAt(0) }, modifier = Modifier.fillMaxSize())
                }
                if (!live) NotRunning(item, viewModel, Modifier.align(Alignment.Center).padding(WhittleSpace.xxl))
                if (live && !consoleOpen) {
                    ConsolePill(errors, Modifier.align(Alignment.BottomCenter).padding(bottom = WhittleSpace.l)) { consoleOpen = true }
                }
            }
            if (consoleOpen) {
                ConsolePanel(
                    console,
                    errors,
                    onAskToFix = {
                        onDismiss()
                        val shown = console.filter { it.error }.takeLast(FIX_LINES).joinToString("\n") { it.text }
                        viewModel.askToFix(fixRequest, PastedKind.PREVIEW_CONSOLE, item.name, shown)
                    },
                    onClear = { console.clear() },
                    onClose = { consoleOpen = false },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewPage(url: String, reloads: Int, live: Boolean, onConsole: (ConsoleLine) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = {
            WebView(it).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        onConsole(ConsoleLine(message.messageLevel(), plainConsoleText(message.message())))
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {
                    // The page's own server stays in the preview; links elsewhere open outside it.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.host == Uri.parse(url).host) return false
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (exception: ActivityNotFoundException) {
                            // Nothing on the phone opens it; the preview stays where it is.
                        }
                        return true
                    }
                }
                tag = PageState(url, reloads)
                if (live) loadUrl(url)
            }
        },
        update = { view ->
            val shown = view.tag as PageState
            when {
                shown.url != url -> view.loadUrl(url)
                shown.reloads != reloads -> view.reload()
            }
            view.tag = PageState(url, reloads)
        },
        onRelease = { it.destroy() },
    )
}

/** What the web view last loaded, so an update reloads only when asked. */
/** One action in the preview's top bar group. */
private class PreviewAction(val icon: String, val label: Int, val onClick: () -> Unit)

private data class PageState(val url: String, val reloads: Int)

@Composable
private fun NotRunning(item: ThreadItem.Process, viewModel: ChatViewModel, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl)).padding(WhittleSpace.xl),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Box(Modifier.size(WhittleSize.Tile.l).background(colors.surfaceContainerHighest, RoundedCornerShape(WhittleRadius.l)), contentAlignment = Alignment.Center) {
                WhittleIcon("power_settings_new")
            }
            Column {
                Text(stringResource(R.string.preview_not_running_title, item.name), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.preview_not_running_body), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        Button(onClick = { viewModel.restartProcess(item) }) {
            WhittleIcon("play_arrow", size = WhittleSize.Icon.m)
            Text(stringResource(R.string.process_restart), modifier = Modifier.padding(start = WhittleSpace.s))
        }
    }
}

@Composable
private fun ConsolePill(errors: Int, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.background(colors.inverseSurface, CircleShape).clickable(onClick = onClick).padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        WhittleIcon("terminal", size = WhittleSize.Icon.m, tint = colors.inverseOnSurface)
        Text(stringResource(R.string.preview_console), style = MaterialTheme.typography.labelLarge, color = colors.inverseOnSurface)
        if (errors > 0) {
            Text(
                pluralStringResource(R.plurals.preview_console_errors, errors, errors),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onError,
                modifier = Modifier.background(colors.error, CircleShape).padding(horizontal = WhittleSpace.s),
            )
        }
    }
}

@Composable
private fun ConsolePanel(console: List<ConsoleLine>, errors: Int, onAskToFix: () -> Unit, onClear: () -> Unit, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().fillMaxHeight(CONSOLE_FRACTION).background(colors.surfaceContainerHigh).padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.preview_console), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.preview_console_hint), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Bar("close", R.string.preview_close, onClick = onClose)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().background(colors.surfaceContainerLowest, RoundedCornerShape(WhittleRadius.l)).padding(WhittleSpace.s),
        ) {
            if (console.isEmpty()) item { Text(stringResource(R.string.preview_console_empty), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            items(console) { line ->
                Text(line.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = if (line.error) colors.error else colors.onSurface)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            if (errors > 0) {
                Button(onClick = onAskToFix) {
                    WhittleIcon("build", size = WhittleSize.Icon.m)
                    Text(stringResource(R.string.preview_ask_fix), modifier = Modifier.padding(start = WhittleSpace.s))
                }
            }
            FilledTonalButton(onClick = onClear) {
                WhittleIcon("block", size = WhittleSize.Icon.m)
                Text(stringResource(R.string.preview_clear), modifier = Modifier.padding(start = WhittleSpace.s))
            }
        }
    }
}

@Composable
private fun Bar(icon: String, label: Int, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) { WhittleIcon(icon, contentDescription = stringResource(label), size = WhittleSize.Icon.l) }
}

private const val MAX_LINES = 200
private const val FIX_LINES = 20
private const val CONSOLE_FRACTION = 0.45f
