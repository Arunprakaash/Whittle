package app.whittle.companion

import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * say so. A server's preview opens in this computer's browser, at the same port tunnelled from the phone.
 */
class ComputerChatPlatform(override val navigation: ChatNavigation) : ChatPlatform {
    /** The latest thing to say briefly, shown at the window's foot until the next. */
    val toasts = MutableStateFlow<String?>(null)

    @Composable
    override fun rememberVoice(input: String, onInput: (String) -> Unit): ComposerVoice? = null

    @Composable
    override fun rememberAttachSources(): Map<AttachSource, () -> Unit> = emptyMap()

    @Composable
    override fun rememberCapabilityRequest(): ((ThreadRow.Capability) -> Unit)? = null

    /** The server opens in this computer's browser, from a tap (browsers only open pages a click asks for). */
    @Composable
    override fun Preview(process: ThreadRow.Process, onDismiss: () -> Unit) {
        val uriHandler = LocalUriHandler.current
        val port = process.run?.port
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(process.name) },
            text = { Text(port?.let { stringResource(Res.string.preview_body, it) } ?: stringResource(Res.string.preview_waiting)) },
            confirmButton = {
                if (port != null) {
                    TextButton(onClick = { uriHandler.openUri("http://$LOOPBACK:$port/"); onDismiss() }) { Text(stringResource(Res.string.preview_open)) }
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
    }
}
