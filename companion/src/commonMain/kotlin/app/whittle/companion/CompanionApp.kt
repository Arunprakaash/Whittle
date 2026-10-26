package app.whittle.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.whittle.chat.ui.AllChatsScreen
import app.whittle.chat.ui.ChatAction
import app.whittle.chat.ui.ChatNavigation
import app.whittle.chat.ui.ChatScreen
import app.whittle.design.window.LocalWindowChrome
import app.whittle.chat.ui.isDark
import app.whittle.companion.resources.Res
import app.whittle.files.ui.FilesBrowser
import app.whittle.files.ui.FilesOpening
import app.whittle.files.ui.FilesPlace
import app.whittle.files.ui.FilesPlatform
import app.whittle.files.ui.filesOpeningSaver
import app.whittle.companion.resources.*
import app.whittle.design.components.BottomActionButton
import app.whittle.design.components.ScreenHeading
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.WhittleFilledField
import app.whittle.design.theme.ThemeMode
import app.whittle.design.theme.WhittleAppearance
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleSpace
import org.jetbrains.compose.resources.stringResource

/** Where a computer reaches the phone, and how it finds the token: the port and file the phone's config names. */
class PhoneAddress(val port: Int, val tokenFile: String) {
    val webSocket: String get() = "ws://127.0.0.1:$port/companion"

    companion object {
        /** The phone's defaults (computer-access.json). */
        val Default = PhoneAddress(port = 8790, tokenFile = "/root/.whittle/companion-token")
    }
}

/** Whittle on a computer: connects to the phone, then shows its chat screen, the very one the phone draws. */
@Composable
fun CompanionApp(link: PhoneLink, phone: PhoneAddress = PhoneAddress.Default, token: String = "") {
    // The phone's look: its light or dark, and its colours, its wallpaper's when they're on.
    val look = link.state.collectAsState().value.look
    val dark = look.isDark()
    WhittleTheme(
        WhittleAppearance(wallpaperColors = false, haptics = false, themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light),
        darkTheme = dark,
        colors = look.scheme(dark),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            when (val state = link.link.collectAsState().value) {
                LinkState.Live -> PhoneScreens(link)
                else -> ConnectScreen(state, phone, token, onConnect = link::connect)
            }
        }
    }
}

/** The phone's screens: the chat, the Chats page, and the file browser for projects and files. */
@Composable
private fun PhoneScreens(link: PhoneLink) {
    var allChats by rememberSaveable { mutableStateOf(false) }
    var opening by rememberSaveable(stateSaver = filesOpeningSaver) { mutableStateOf<FilesOpening?>(null) }
    val files = link.files.collectAsState().value
    val platform = remember(files != null) {
        ComputerChatPlatform(
            ChatNavigation(
                viewAllChats = { allChats = true },
                openProjects = files?.let { { opening = FilesOpening(FilesPlace.Folder("")) } },
                openFile = files?.let { { path, chat -> opening = FilesOpening(FilesPlace.Document(path), chat) } },
                openFolder = files?.let { { path, chat -> opening = FilesOpening(FilesPlace.Folder(path), chat) } },
            ),
        )
    }
    val open = opening
    when {
        open != null && files != null -> FilesBrowser(
            host = files,
            // Sharing and the device's file manager are the phone's; here the browser only reads.
            platform = remember { FilesPlatform(toast = platform::toast) },
            opening = open,
            onAsk = { link.act(ChatAction.SendText(it)) },
            onClose = { opening = null },
        )
        allChats -> AllChatsScreen(
            state = link.state.collectAsState().value,
            onAction = link::act,
            onBack = { allChats = false },
            onOpen = { id -> link.act(ChatAction.OpenChat(id)); allChats = false },
        )
        else -> ChatScreen(link, platform)
    }
}

@Composable
private fun ConnectScreen(state: LinkState, phone: PhoneAddress, token: String, onConnect: (String, String) -> Unit) {
    var address by rememberSaveable { mutableStateOf(phone.webSocket) }
    var secret by rememberSaveable { mutableStateOf(token) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Where the app draws the window's top, it still moves the window.
        LocalWindowChrome.current?.let { chrome ->
            chrome.Draggable { Box(Modifier.fillMaxWidth().windowInsetsTopHeight(chrome.insets).align(Alignment.TopCenter)) }
        }
        Column(Modifier.widthIn(max = FORM_WIDTH).padding(WhittleSpace.l), verticalArrangement = Arrangement.spacedBy(WhittleSpace.l)) {
            ScreenHeading(stringResource(Res.string.connect_title), stringResource(Res.string.connect_body, phone.port, phone.tokenFile))
            WhittleFilledField(address, { address = it }, stringResource(Res.string.connect_address), singleLine = true)
            WhittleFilledField(secret, { secret = it.trim() }, stringResource(Res.string.connect_token), singleLine = true)
            (state as? LinkState.Off)?.problem?.let { problem ->
                val words = when (problem) {
                    LinkProblem.Refused -> Res.string.connect_refused
                    LinkProblem.Unreachable -> Res.string.connect_failed
                    LinkProblem.Lost -> Res.string.connect_lost
                }
                SupportingNote("error", stringResource(words))
            }
            BottomActionButton(
                icon = "computer",
                label = stringResource(if (state == LinkState.Connecting) Res.string.connect_connecting else Res.string.connect_go),
                onClick = { if (secret.isNotBlank()) onConnect(address.trim(), secret) },
            )
        }
    }
}

private val FORM_WIDTH = 520.dp
