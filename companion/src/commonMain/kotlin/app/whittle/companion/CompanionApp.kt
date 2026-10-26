package app.whittle.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.DisposableEffect
import app.whittle.connect.BringModelScreen
import app.whittle.connect.ModelPickerScreen
import app.whittle.connect.PickerAction
import app.whittle.files.ui.FilesBrowser
import app.whittle.linux.ui.LinuxPlatform
import app.whittle.linux.ui.LinuxWorkspaceScreen
import app.whittle.settings.ui.SettingsNavigation
import app.whittle.settings.ui.SettingsScreen
import app.whittle.files.ui.FilesOpening
import app.whittle.files.ui.FilesPlace
import app.whittle.files.ui.FilesPlatform
import app.whittle.files.ui.filesOpeningSaver
import app.whittle.companion.resources.*
import app.whittle.design.theme.ThemeMode
import app.whittle.design.theme.WhittleAppearance
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleSpace
import org.jetbrains.compose.resources.stringResource

/** Where a computer reaches the phone, and how it finds the token: the port and file the phone's config names. */
class PhoneAddress(val port: Int, val tokenFile: String, val sshPort: Int, val user: String) {
    val webSocket: String get() = "ws://127.0.0.1:$port/companion"

    /** Opens the link's port from this computer to the phone at [host], and stays open (-N) while the terminal does. */
    fun tunnel(host: String): String = "ssh -p $sshPort -N -L $port:127.0.0.1:$port $user@$host"

    /** Prints the token the phone at [host] keeps in Linux. */
    fun readToken(host: String): String = "ssh -p $sshPort $user@$host cat $tokenFile"

    companion object {
        /** The phone's defaults (computer-access.json): the link's port and token, and its SSH server's. */
        val Default = PhoneAddress(port = 8790, tokenFile = "/root/.whittle/companion-token", sshPort = 8022, user = "root")
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

/** The phone's screens: the chat, the Chats page, the file browser for projects and files, and Settings. */
@Composable
private fun PhoneScreens(link: PhoneLink) {
    var allChats by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var linux by rememberSaveable { mutableStateOf(false) }
    var opening by rememberSaveable(stateSaver = filesOpeningSaver) { mutableStateOf<FilesOpening?>(null) }
    val files = link.files.collectAsState().value
    val platform = remember(files != null) {
        ComputerChatPlatform(
            ChatNavigation(
                viewAllChats = { allChats = true },
                openSettings = { settings = true },
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
        picking -> ModelPicking(link, onClose = { picking = false })
        // The sign-in link opens from the row's Open link: a page opened by itself here would be blocked.
        linux -> LinuxWorkspaceScreen(link.linux, remember { LinuxPlatform(opensLinksItself = false) }, onBack = { linux = false })
        settings -> SettingsScreen(
            host = link.settings,
            platform = remember { ComputerSettingsPlatform() },
            navigation = SettingsNavigation(back = { settings = false }, changeModel = { picking = true }, linux = { linux = true }),
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

/**
 * Changing the model, from Settings: the phone's picker for this computer, its models listed with the saved key; Use
 * another lists the providers, and a key typed here goes to the phone to be checked and kept.
 */
@Composable
private fun ModelPicking(link: PhoneLink, onClose: () -> Unit) {
    val picker = remember { link.openPicker() }
    DisposableEffect(picker) { onDispose { link.closePicker() } }
    var choosing by rememberSaveable { mutableStateOf(false) }
    if (choosing) {
        val state = picker.state.collectAsState().value
        BringModelScreen(
            providers = state.providers,
            selected = state.providerId,
            claudeCode = null,
            onProvider = { picker.act(PickerAction.SelectProvider(it)); choosing = false },
            onClaudeCode = {},
            onBack = { choosing = false },
        )
    } else {
        ModelPickerScreen(picker, onClose = onClose, onUseAnother = { choosing = true })
    }
}

