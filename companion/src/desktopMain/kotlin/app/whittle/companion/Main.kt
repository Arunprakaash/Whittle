package app.whittle.companion

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.WindowChrome

/**
 * The Mac app. It opens the tunnel to the phone itself, with this computer's ssh (WHITTLE_SSH names another);
 * WHITTLE_PHONE and WHITTLE_TOKEN, when set, connect at once to a tunnel opened by hand instead.
 */
fun main() = application {
    val scope = rememberCoroutineScope()
    val link = remember { PhoneLink(scope) }
    val tunnel = remember { SshTunnel(PhoneAddress.Default, ssh = System.getenv(SSH_VARIABLE) ?: SshTunnel.SSH) }
    val address = System.getenv(ADDRESS_VARIABLE) ?: PhoneAddress.Default.webSocket
    val token = System.getenv(TOKEN_VARIABLE).orEmpty()
    LaunchedEffect(Unit) { if (token.isNotBlank()) link.connect(address, token) }
    // The tunnel goes with the app, so nothing is left listening once it's closed.
    Window(onCloseRequest = { tunnel.close(); exitApplication() }, title = TITLE, state = rememberWindowState(width = 1200.dp, height = 900.dp)) {
        CompositionLocalProvider(LocalWindowChrome provides rememberMacChrome()) {
            CompanionApp(link, token = token, tunnel = tunnel.takeIf { token.isBlank() })
        }
    }
}

/**
 * On a Mac the window's title bar goes, and the app's own top bar takes its place under the window's buttons, in the
 * app's colours, as Mac apps do; dragging that bar moves the window. Elsewhere the system's title bar stays.
 */
@Composable
private fun FrameWindowScope.rememberMacChrome(): WindowChrome? = remember {
    if (!System.getProperty(OS_NAME).orEmpty().startsWith(MAC)) return@remember null
    val frame = this
    window.rootPane.apply {
        putClientProperty("apple.awt.fullWindowContent", true)
        putClientProperty("apple.awt.transparentTitleBar", true)
        putClientProperty("apple.awt.windowTitleVisible", false)
    }
    object : WindowChrome {
        override val insets = WindowInsets(top = MAC_TITLE_BAR)

        @Composable
        override fun Draggable(content: @Composable () -> Unit) = with(frame) { WindowDraggableArea { content() } }
    }
}

private const val TITLE = "Whittle"
private const val OS_NAME = "os.name"
private const val MAC = "Mac"

/** The height of a Mac window's title bar, where its close, minimise and zoom buttons sit. */
private val MAC_TITLE_BAR = 28.dp
private const val ADDRESS_VARIABLE = "WHITTLE_PHONE"
private const val TOKEN_VARIABLE = "WHITTLE_TOKEN"
private const val SSH_VARIABLE = "WHITTLE_SSH"
