package app.whittle.companion

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import app.whittle.chat.ui.AppInstalls
import app.whittle.chat.ui.AttachSource
import app.whittle.chat.ui.ChatNavigation
import app.whittle.chat.ui.ChatPlatform
import app.whittle.chat.ui.ThreadRow
import app.whittle.companion.resources.Res
import app.whittle.companion.resources.*
import app.whittle.design.components.ComposerVoice
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * What a computer does for the chat screen: no mic, installer or Android prompts, which stay on the phone, and the cards
 * say so. A server's preview opens in this computer's browser: through [previews], the app's own tunnel, when it has
 * one (the Mac app), or through a tunnel the user opens in a terminal (a browser), at the same port as on the phone.
 */
class ComputerChatPlatform(
    override val navigation: ChatNavigation,
    private val phone: PhoneAddress = PhoneAddress.Default,
    private val previews: PhoneTunnel? = null,
) : ChatPlatform {
    /** The latest thing to say briefly, shown at the window's foot until the next. */
    val toasts = MutableStateFlow<String?>(null)

    @Composable
    override fun rememberVoice(input: String, onInput: (String) -> Unit): ComposerVoice? = null

    @Composable
    override fun rememberAttachSources(): Map<AttachSource, () -> Unit> = emptyMap()

    @Composable
    override fun rememberCapabilityRequest(): ((ThreadRow.Capability) -> Unit)? = null

    @Composable
    override fun Preview(process: ThreadRow.Process, onDismiss: () -> Unit) {
        val port = process.run?.port
        val tunnel = previews
        if (port != null && tunnel != null) {
            ForwardedPreview(process.name, port, tunnel, onDismiss)
        } else {
            ManualPreview(process.name, port, onDismiss)
        }
    }

    /** The app forwards the server's port itself, then opens the page; the dialog only shows while it does, or if it can't. */
    @Composable
    private fun ForwardedPreview(name: String, port: Int, tunnel: PhoneTunnel, onDismiss: () -> Unit) {
        val uriHandler = LocalUriHandler.current
        var attempt by remember { mutableStateOf(0) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(attempt) {
            failed = false
            val local = tunnel.forward(port)
            if (local != null) {
                uriHandler.openUri(page(local))
                onDismiss()
            } else {
                failed = true
            }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(name) },
            text = {
                Text(
                    if (failed) {
                        stringResource(Res.string.preview_failed, port, phone.forward(port, stringResource(Res.string.connect_host_placeholder)))
                    } else {
                        stringResource(Res.string.preview_opening, port)
                    },
                )
            },
            confirmButton = {
                if (failed) TextButton(onClick = { attempt++ }) { Text(stringResource(Res.string.preview_retry)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.preview_cancel)) } },
        )
    }

    /** The tunnel to open in a terminal, then the page from a tap (browsers only open pages a click asks for). */
    @Composable
    private fun ManualPreview(name: String, port: Int?, onDismiss: () -> Unit) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(name) },
            text = {
                Text(
                    port?.let { stringResource(Res.string.preview_body, it, phone.forward(it, stringResource(Res.string.connect_host_placeholder))) }
                        ?: stringResource(Res.string.preview_waiting),
                )
            },
            confirmButton = {
                if (port != null) {
                    TextButton(onClick = { uriHandler.openUri(page(port)); onDismiss() }) { Text(stringResource(Res.string.preview_open)) }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.preview_cancel)) } },
        )
    }

    override val apps: AppInstalls? = null

    override val share: ((String, String) -> Unit)? = null

    override fun toast(message: String) {
        toasts.value = message
    }

    @Composable
    override fun AboveComposer() = Unit

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        fun page(port: Int) = "http://$LOOPBACK:$port/"
    }
}
