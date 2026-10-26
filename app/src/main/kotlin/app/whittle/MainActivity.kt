package app.whittle

import app.whittle.memory.ui.MemoryScreen
import android.content.res.Configuration
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import android.view.WindowManager
import android.os.Build
import app.whittle.connections.ui.ConnectionLibraryScreen
import app.whittle.connections.ui.ConnectionToolsScreen
import app.whittle.design.theme.isDark
import androidx.compose.runtime.DisposableEffect
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.compose.runtime.mutableIntStateOf
import app.whittle.connect.ModelPickerScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import app.whittle.background.rememberNotificationPermissionRequest
import app.whittle.design.theme.WhittleAppearance
import app.whittle.design.components.LocalSiteIcons
import androidx.compose.runtime.CompositionLocalProvider
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.whittle.automations.rememberPhoneAutomationsPlatform
import app.whittle.automations.ui.AutomationsNavigation
import app.whittle.automations.ui.AutomationsScreen
import app.whittle.chat.rememberPhoneChatPlatform
import app.whittle.storage.chats.ChatId
import app.whittle.chat.ui.AllChatsScreen
import app.whittle.settings.rememberPhoneConnectionsPlatform
import app.whittle.settings.rememberPhoneSettingsPlatform
import app.whittle.settings.ui.SettingsNavigation
import app.whittle.settings.ui.SettingsScreen
import app.whittle.chat.ui.ChatNavigation
import app.whittle.chat.ui.ChatScreen
import app.whittle.design.window.LocalUserContent
import app.whittle.observability.posthog.userContent
import androidx.compose.runtime.CompositionLocalProvider
import app.whittle.connect.ConnectScreen
import app.whittle.connect.ConnectViewModel
import app.whittle.design.theme.WhittleTheme
import androidx.compose.foundation.layout.padding
import app.whittle.design.tokens.WhittleSpace
import app.whittle.files.WorkspaceDocumentsProvider
import app.whittle.files.shareFile
import app.whittle.files.ui.FilesBrowser
import app.whittle.files.ui.FilesOpening
import app.whittle.files.ui.FilesPlace
import app.whittle.files.ui.FilesPlatform
import app.whittle.files.ui.filesOpeningSaver
import app.whittle.linux.LinuxProgressStrip
import app.whittle.linux.LinuxSetupScreen
import app.whittle.onboarding.ONBOARDING_STEPS
import app.whittle.onboarding.ONBOARDING_STEP_LINUX
import app.whittle.onboarding.ONBOARDING_STEP_MODEL
import app.whittle.onboarding.OnboardingSteps
import app.whittle.onboarding.WelcomeScreen
import app.whittle.linux.ui.LinuxPlatform
import app.whittle.linux.ui.LinuxWorkspaceScreen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import android.content.Intent
import app.whittle.chat.AppShortcuts
import app.whittle.chat.ComposerStart
import app.whittle.chat.ShortcutOpening
import app.whittle.quickstart.QuickStart
import app.whittle.attachments.SharedItems
import app.whittle.core.common.getOrNull
import app.whittle.core.model.ProviderId
import app.whittle.storage.secrets.SecretKey
import app.whittle.connect.BringModelScreen
import app.whittle.connect.ProviderEntry
import app.whittle.connect.ClaudeCodeChoice
import app.whittle.claudecode.ClaudeCodeEntry
import app.whittle.claudecode.ClaudeCodeFlow
import app.whittle.claudecode.ClaudeCodePickerScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** What another app shared, until the chat takes it. */
    private val shared = MutableStateFlow<SharedItems?>(null)

    /** The app a home-screen shortcut opens, until the chat takes it. */
    private val openingApp = MutableStateFlow<ShortcutOpening?>(null)
    /** A notification asked for Settings, where the connections are. */
    private val openingConnections = MutableStateFlow(false)

    /** A new chat asked for from the launcher icon or Quick Settings, until the chat takes it. */
    private val quickStart = MutableStateFlow<QuickStart?>(null)

    /** Set when the assistant opened Whittle, until the chat takes the screen it read. */
    private val assisting = MutableStateFlow(false)

    /** The assistant opened Whittle: through [QuickStart.ENTRY], which only Whittle can start, like the quick starts. */
    private fun isAssist(intent: Intent): Boolean = intent.action == ACTION_ASSIST && intent.component?.className == QuickStart.ENTRY

    /** The update notification's Update button: through [QuickStart.ENTRY] too, so no other app can start a download. */
    private fun isUpdate(intent: Intent): Boolean = intent.action == ACTION_UPDATE && intent.component?.className == QuickStart.ENTRY

    /** Set when the update notification asked to update, until Settings shows it under way. */
    private val updating = MutableStateFlow(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        QuickStart.from(intent.action, intent.component?.className)?.let { quickStart.value = it }
        if (intent.action == ACTION_SHOW_CONNECTIONS) openingConnections.value = true
        if (isAssist(intent)) assisting.value = true
        if (isUpdate(intent)) updating.value = true
        SharedItems.from(intent)?.let { shared.value = it }
        AppShortcuts.opening(intent)?.let { openingApp.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // A share or a shortcut is taken once: a screen rebuilt after rotating mustn't take it again.
        if (savedInstanceState == null) {
            shared.value = SharedItems.from(intent)
            openingApp.value = AppShortcuts.opening(intent)
            quickStart.value = QuickStart.from(intent?.action, intent?.component?.className)
            openingConnections.value = intent?.action == ACTION_SHOW_CONNECTIONS
            assisting.value = intent?.let(::isAssist) == true
            updating.value = intent?.let(::isUpdate) == true
        }
        drawBehindSystemBars(dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        val graph = (application as WhittleApplication).graph
        setContent {
            // Until saved settings load, the bundled defaults apply; a changed setting restyles at once.
            val settings by graph.currentSettings.collectAsStateWithLifecycle()
            val appearance = settings?.let { WhittleAppearance(wallpaperColors = it.wallpaperColors, haptics = it.haptics, themeMode = it.themeMode) }
                ?: graph.defaultAppearance
            val dark = appearance.themeMode.isDark()
            // The bars' icons follow the app's theme, which may differ from the phone's.
            DisposableEffect(dark) {
                drawBehindSystemBars(dark)
                onDispose { }
            }
            WhittleTheme(appearance, darkTheme = dark) {
                val gate by remember {
                    combine(graph.connections.model, graph.settings.settings) { model, saved ->
                        when {
                            // First run offers Linux before the model, so it installs while the user connects one.
                            // An existing user, already connected, sees the offer once after updating.
                            !saved.linuxOffered -> if (model == null) Gate.Welcome else Gate.Linux
                            model == null -> Gate.Connect
                            else -> Gate.Chat
                        }
                    }
                }.collectAsStateWithLifecycle(Gate.Loading)
                var welcomed by rememberSaveable { mutableStateOf(false) }
                // The picker and Connect keep their state in view models keyed by how often they've opened. The counts
                // live out here so they never start again when the gate moves (switching provider passes through
                // Connect): a key used before would bring back that old screen, with the provider it had then.
                var pickerOpenings by rememberSaveable { mutableIntStateOf(0) }
                var connectOpenings by rememberSaveable { mutableIntStateOf(0) }
                // Surface sets the content color, so text on every screen follows the theme in light and dark.
                CompositionLocalProvider(LocalSiteIcons provides graph.siteIcons) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    when (gate) {
                        Gate.Loading -> Unit
                        Gate.Welcome -> if (welcomed) {
                            LinuxSetupScreen(graph, step = ONBOARDING_STEP_LINUX)
                        } else {
                            WelcomeScreen(onStart = { welcomed = true })
                        }
                        Gate.Linux -> LinuxSetupScreen(graph, step = null)
                        Gate.Connect -> {
                            var claudeCodeStep by rememberSaveable { mutableStateOf(ClaudeCodeStep.None) }
                            val connectScope = rememberCoroutineScope()
                            // Signed in: Claude Code's first model is chosen, and the chat opens.
                            val useClaudeCode = { connectScope.launch { graph.connections.save(graph.claudeCode.models.first()) }; Unit }
                            when (claudeCodeStep) {
                                ClaudeCodeStep.Setup, ClaudeCodeStep.SignIn -> ClaudeCodeFlow(
                                    graph.claudeCode,
                                    entry = claudeCodeStep.entry,
                                    onBack = { claudeCodeStep = ClaudeCodeStep.None },
                                    onReady = useClaudeCode,
                                )
                                ClaudeCodeStep.None -> {
                                    val connect = viewModel(key = "connect$connectOpenings") { ConnectViewModel(graph) }
                                    ConnectScreen(
                                        connect.picker,
                                        header = {
                                            LinuxProgressStrip(graph, compact = false, Modifier.padding(top = WhittleSpace.s))
                                            OnboardingSteps(ONBOARDING_STEP_MODEL, ONBOARDING_STEPS, Modifier.padding(vertical = WhittleSpace.xl))
                                        },
                                        claudeCode = graph.claudeCode.config.displayName.takeIf { graph.claudeCode.config.enabled },
                                        onClaudeCode = { claudeCodeStep = ClaudeCodeStep.Setup },
                                    )
                                }
                            }
                        }
                        Gate.Chat -> {
                            // The app owns the chat session, so a task outlives this screen.
                            val chat = (application as WhittleApplication).chat
                            val chatHost = (application as WhittleApplication).chatHost
                            // The first task asks once for notifications, so its background progress can show.
                            val requestNotifications = rememberNotificationPermissionRequest()
                            val running = chat.state.collectAsStateWithLifecycle().value.running
                            var askedForNotifications by rememberSaveable { mutableStateOf(false) }
                            LaunchedEffect(running) {
                                if (running && !askedForNotifications && settings?.keepRunningInBackground == true) {
                                    askedForNotifications = true
                                    requestNotifications()
                                }
                            }
                            val state = chat.state.collectAsStateWithLifecycle().value
                            var showingAllChats by rememberSaveable { mutableStateOf(false) }
                            var showingSettings by rememberSaveable { mutableStateOf(false) }
                            var showingModelPicker by rememberSaveable { mutableStateOf(false) }
                            var showingConnectionLibrary by rememberSaveable { mutableStateOf(false) }
                            var showingMemory by rememberSaveable { mutableStateOf(false) }
                            var showingLinux by rememberSaveable { mutableStateOf(false) }
                            var showingAutomations by rememberSaveable { mutableStateOf(false) }
                            val scope = rememberCoroutineScope()
                            var toolsOf by rememberSaveable { mutableStateOf<String?>(null) }
                            var files by rememberSaveable(stateSaver = filesOpeningSaver) { mutableStateOf<FilesOpening?>(null) }
                            val connections by graph.connectors.all.collectAsStateWithLifecycle()
                            var pickerFromChat by rememberSaveable { mutableStateOf(false) }
                            // The provider the model picker shows when it isn't the current model's (back from Claude Code).
                            var pickerProvider by remember { mutableStateOf<ProviderId?>(null) }
                            var choosingProvider by rememberSaveable { mutableStateOf(false) }
                            var switchingTo by remember { mutableStateOf<ProviderId?>(null) }
                            var claudeCodeStep by rememberSaveable { mutableStateOf(ClaudeCodeStep.None) }
                            val incoming by shared.collectAsStateWithLifecycle()
                            LaunchedEffect(incoming) {
                                val items = incoming ?: return@LaunchedEffect
                                shared.value = null
                                // A share lands in a new chat, whatever screen was open.
                                files = null
                                showingSettings = false; showingAllChats = false; showingModelPicker = false; showingAutomations = false
                                showingMemory = false; showingLinux = false; showingConnectionLibrary = false; toolsOf = null
                                chat.startShared(items)
                            }
                            val shortcut by openingApp.collectAsStateWithLifecycle()
                            LaunchedEffect(shortcut) {
                                val opening = shortcut ?: return@LaunchedEffect
                                openingApp.value = null
                                // The app opens over its chat, whatever screen was open.
                                files = null
                                showingSettings = false; showingAllChats = false; showingModelPicker = false; showingAutomations = false
                                showingMemory = false; showingLinux = false; showingConnectionLibrary = false; toolsOf = null
                                chat.openApp(opening)
                            }
                            val starting by quickStart.collectAsStateWithLifecycle()
                            LaunchedEffect(starting) {
                                val start = starting ?: return@LaunchedEffect
                                quickStart.value = null
                                start.reportUsed(this@MainActivity)
                                // A quick start lands in a new chat, whatever screen was open.
                                files = null
                                showingSettings = false; showingAllChats = false; showingModelPicker = false; showingAutomations = false
                                showingMemory = false; showingLinux = false; showingConnectionLibrary = false; toolsOf = null
                                chat.newChat(if (start == QuickStart.SPEAK) ComposerStart.LISTEN else ComposerStart.TYPE)
                            }
                            val toConnections by openingConnections.collectAsStateWithLifecycle()
                            LaunchedEffect(toConnections) {
                                if (!toConnections) return@LaunchedEffect
                                openingConnections.value = false
                                files = null
                                showingAllChats = false; showingModelPicker = false; showingAutomations = false
                                showingMemory = false; showingLinux = false; showingConnectionLibrary = false; toolsOf = null
                                showingSettings = true
                            }
                            val update by updating.collectAsStateWithLifecycle()
                            LaunchedEffect(update) {
                                if (!update) return@LaunchedEffect
                                updating.value = false
                                // Settings, where the Version row shows the download and the install.
                                files = null
                                showingAllChats = false; showingModelPicker = false; showingAutomations = false
                                showingMemory = false; showingLinux = false; showingConnectionLibrary = false; toolsOf = null
                                showingSettings = true
                                graph.selfUpdate.start()
                            }
                            val assisted by assisting.collectAsStateWithLifecycle()
                            LaunchedEffect(assisted) {
                                if (!assisted) return@LaunchedEffect
                                assisting.value = false
                                val screen = (application as WhittleApplication).assistIncoming.getAndUpdate { null } ?: return@LaunchedEffect
                                // The assistant lands in a new chat, whatever screen was open.
                                files = null
                                showingSettings = false; showingAllChats = false; showingModelPicker = false; showingAutomations = false
                                showingMemory = false; showingLinux = false; showingConnectionLibrary = false; toolsOf = null
                                chat.startWithScreen(screen)
                            }
                            val toolsConnection = toolsOf?.takeIf { id -> connections.any { it.server.id == id } }
                            val opening = files
                            if (opening != null) {
                                val context = this@MainActivity
                                val filesPlatform = remember {
                                    FilesPlatform(
                                        share = { path, name -> shareFile(context, path, name) },
                                        openInFiles = { WorkspaceDocumentsProvider.open(context) },
                                        toast = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
                                    )
                                }
                                FilesBrowser((application as WhittleApplication).filesHost, filesPlatform, opening, onAsk = chat::sendText, onClose = { files = null })
                            } else if (showingAutomations) {
                                LaunchedEffect(Unit) { chat.refreshProjects() }
                                AutomationsScreen(
                                    host = (application as WhittleApplication).automationsHost,
                                    platform = rememberPhoneAutomationsPlatform(graph),
                                    navigation = AutomationsNavigation(
                                        back = { showingAutomations = false; showingSettings = true },
                                        openChat = { id -> chat.openChat(ChatId(id)); showingAutomations = false; showingSettings = false },
                                        runStarted = { showingAutomations = false; showingSettings = false },
                                    ),
                                )
                            } else if (showingMemory) {
                                MemoryScreen((application as WhittleApplication).memoryHost, onBack = { showingMemory = false; showingSettings = true })
                            } else if (showingLinux) {
                                LinuxWorkspaceScreen((application as WhittleApplication).linuxHost, LinuxPlatform(opensLinksItself = true), onBack = { showingLinux = false; showingSettings = true })
                            } else if (showingConnectionLibrary) {
                                ConnectionLibraryScreen((application as WhittleApplication).connectionsHost, rememberPhoneConnectionsPlatform(), onBack = { showingConnectionLibrary = false; showingSettings = true })
                            } else if (toolsConnection != null) {
                                ConnectionToolsScreen((application as WhittleApplication).connectionsHost, toolsConnection, onBack = { toolsOf = null; showingSettings = true })
                            } else if (showingModelPicker && choosingProvider) {
                                val claudeCodeInUse = graph.claudeCode.runs(state.model) && pickerProvider == null
                                val shownProvider = pickerProvider ?: state.model?.providerId?.takeUnless { claudeCodeInUse }
                                BringModelScreen(
                                    providers = graph.providers.providers.map { ProviderEntry(it.id.value, it.displayName, it.tagline, it.keysUrl) },
                                    selected = shownProvider?.value,
                                    claudeCode = ClaudeCodeChoice(graph.claudeCode.config.displayName, claudeCodeInUse).takeIf { graph.claudeCode.config.enabled },
                                    onProvider = { picked ->
                                        val id = ProviderId(picked)
                                        scope.launch {
                                            // A provider whose key is still saved lists its models; another one is connected with its key.
                                            if (graph.secrets.get(SecretKey.providerApiKey(id)).getOrNull() != null) {
                                                pickerProvider = id.takeUnless { it == state.model?.providerId }
                                                pickerOpenings += 1
                                                choosingProvider = false
                                            } else {
                                                switchingTo = id
                                            }
                                        }
                                    },
                                    onClaudeCode = {
                                        choosingProvider = false
                                        if (!claudeCodeInUse) claudeCodeStep = ClaudeCodeStep.Setup
                                    },
                                    onBack = { choosingProvider = false },
                                )
                                switchingTo?.let { target ->
                                    val leaving = graph.providers.providers.firstOrNull { it.id == state.model?.providerId }
                                    if (leaving == null) {
                                        // Nothing to forget (Claude Code is in use): connect the new provider.
                                        LaunchedEffect(target) {
                                            switchingTo = null
                                            choosingProvider = false
                                            showingModelPicker = false
                                            pickerFromChat = false
                                            pickerProvider = null
                                            connectOpenings += 1
                                            chat.switchProvider()
                                        }
                                    } else {
                                        AlertDialog(
                                            onDismissRequest = { switchingTo = null },
                                            title = { Text(stringResource(R.string.model_picker_switch_title)) },
                                            text = { Text(stringResource(R.string.model_picker_switch_body, leaving.displayName)) },
                                            confirmButton = {
                                                TextButton(onClick = {
                                                    switchingTo = null
                                                    choosingProvider = false
                                                    showingModelPicker = false
                                                    pickerFromChat = false
                                                    pickerProvider = null
                                                    connectOpenings += 1
                                                    chat.switchProvider()
                                                }) { Text(stringResource(R.string.model_picker_switch_confirm)) }
                                            },
                                            dismissButton = { TextButton(onClick = { switchingTo = null }) { Text(stringResource(R.string.model_picker_cancel)) } },
                                        )
                                    }
                                }
                            } else if (showingModelPicker && claudeCodeStep != ClaudeCodeStep.None) {
                                // Bringing Claude Code from the model picker: the API key stays saved for switching back.
                                val useClaudeCode = {
                                    scope.launch { graph.connections.save(graph.claudeCode.models.first()) }
                                    claudeCodeStep = ClaudeCodeStep.None
                                    pickerProvider = null
                                }
                                ClaudeCodeFlow(
                                    graph.claudeCode,
                                    entry = claudeCodeStep.entry,
                                    onBack = { claudeCodeStep = ClaudeCodeStep.None },
                                    onReady = useClaudeCode,
                                )
                            } else if (showingModelPicker && pickerProvider == null && graph.claudeCode.runs(state.model)) {
                                val closePicker = { showingModelPicker = false; showingSettings = !pickerFromChat; pickerFromChat = false }
                                ClaudeCodePickerScreen(
                                    graph.claudeCode,
                                    current = state.model,
                                    onChoose = { model -> scope.launch { graph.connections.save(model) }; closePicker() },
                                    onSignIn = { claudeCodeStep = ClaudeCodeStep.SignIn },
                                    onUseAnother = { choosingProvider = true },
                                    onClose = closePicker,
                                )
                            } else if (showingModelPicker) {
                                // A fresh picker each time it opens, so its models are listed again.
                                val picker = viewModel(key = "modelPicker$pickerOpenings") {
                                    ConnectViewModel(graph, current = state.model, provider = pickerProvider ?: state.model?.providerId)
                                }
                                ModelPickerScreen(
                                    host = picker.picker,
                                    onClose = { showingModelPicker = false; showingSettings = !pickerFromChat; pickerFromChat = false; pickerProvider = null },
                                    onUseAnother = { choosingProvider = true },
                                )
                            } else if (showingSettings) {
                                val app = application as WhittleApplication
                                SettingsScreen(
                                    host = app.settingsHost,
                                    platform = rememberPhoneSettingsPlatform(
                                        graph,
                                        app.connectionsHost,
                                        onBrowseConnections = { showingSettings = false; showingConnectionLibrary = true },
                                        onConnectionTools = { id -> showingSettings = false; toolsOf = id },
                                    ),
                                    navigation = SettingsNavigation(
                                        back = { showingSettings = false },
                                        changeModel = { showingSettings = false; pickerOpenings += 1; showingModelPicker = true },
                                        linux = { showingSettings = false; showingLinux = true },
                                        memory = { showingSettings = false; showingMemory = true },
                                        automations = { showingSettings = false; showingAutomations = true },
                                    ),
                                )
                            } else if (showingAllChats) {
                                BackHandler { showingAllChats = false }
                                AllChatsScreen(
                                    state = chatHost.state.collectAsStateWithLifecycle().value,
                                    onAction = chatHost::act,
                                    onBack = { showingAllChats = false },
                                    onOpen = { id -> chat.openChat(ChatId(id)); showingAllChats = false },
                                )
                            } else {
                                val navigation = remember {
                                    ChatNavigation(
                                        viewAllChats = { showingAllChats = true },
                                        openSettings = { showingSettings = true },
                                        openProjects = { files = FilesOpening(FilesPlace.Folder("")) },
                                        openFile = { path, chatId -> files = FilesOpening(FilesPlace.Document(path), chatId) },
                                        openFolder = { path, chatId -> files = FilesOpening(FilesPlace.Folder(path), chatId) },
                                        changeModel = { pickerFromChat = true; pickerOpenings += 1; showingModelPicker = true },
                                    )
                                }
                                // What the user and the agent wrote stays out of session recordings.
                                CompositionLocalProvider(LocalUserContent provides Modifier.userContent()) {
                                    ChatScreen(chatHost, rememberPhoneChatPlatform(chat, graph, navigation))
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }

    /** Whether a model is connected and Linux was offered yet; Loading avoids flashing a first-run screen at launch. */
    private enum class Gate { Loading, Welcome, Linux, Connect, Chat }

    /** Bringing Claude Code instead of an API key: its setup, then its own sign-in. */
    /** Which part of Claude Code's setup is open: from its first screen, or straight at how to sign in. */
    private enum class ClaudeCodeStep(val entry: ClaudeCodeEntry) { None(ClaudeCodeEntry.SETUP), Setup(ClaudeCodeEntry.SETUP), SignIn(ClaudeCodeEntry.SIGN_IN) }

    companion object {
        /** Opens Settings, where the connections are; sent by the notification for a connection that signed Whittle out. */
        const val ACTION_SHOW_CONNECTIONS = "app.whittle.action.SHOW_CONNECTIONS"
        const val ACTION_UPDATE = "app.whittle.action.UPDATE"

        /** Opens a new chat about the screen the assistant just read ([WhittleApplication.assistIncoming]). */
        const val ACTION_ASSIST = "app.whittle.action.ASSIST_SCREEN"
    }
}

/**
 * Draws the app behind the status and navigation bars, with bar icons that follow the app's theme. On Android 15+
 * the library's enableEdgeToEdge also adds a scrim view on every call (transparent here, so it shows nothing);
 * that view makes every session-replay screenshot fail PostHog's mask check, which left recordings black, and
 * each theme change would stack another one. So there the window is set up directly instead.
 */
internal fun ComponentActivity.drawBehindSystemBars(dark: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        return
    }
    WindowCompat.setDecorFitsSystemWindows(window, false)
    // Android makes the bars transparent by itself only for apps targeting 35+; Whittle targets 28 for Linux.
    @Suppress("DEPRECATION")
    window.statusBarColor = Color.TRANSPARENT
    @Suppress("DEPRECATION")
    window.navigationBarColor = Color.TRANSPARENT
    // As enableEdgeToEdge does for a transparent, theme-following bar: the system keeps 3-button navigation legible.
    window.isNavigationBarContrastEnforced = true
    window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
    WindowInsetsControllerCompat(window, window.decorView).run {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}
