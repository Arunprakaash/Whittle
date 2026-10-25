package app.whittle

import app.whittle.settings.MemoryScreen
import android.content.res.Configuration
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import android.view.WindowManager
import android.os.Build
import app.whittle.settings.ConnectionToolsScreen
import app.whittle.settings.ConnectionLibraryScreen
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
import app.whittle.settings.SettingsScreen
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
import app.whittle.automations.AutomationsScreen
import app.whittle.automations.RunNowResult
import app.whittle.chat.AllChatsScreen
import app.whittle.chat.ChatScreen
import app.whittle.connect.ConnectScreen
import app.whittle.connect.ConnectViewModel
import app.whittle.design.theme.WhittleTheme
import androidx.compose.foundation.layout.padding
import app.whittle.design.tokens.WhittleSpace
import app.whittle.files.FilesBrowser
import app.whittle.files.FilesOpening
import app.whittle.files.FilesPlace
import app.whittle.files.filesOpeningSaver
import app.whittle.linux.LinuxProgressStrip
import app.whittle.linux.LinuxSetupScreen
import app.whittle.onboarding.ONBOARDING_STEPS
import app.whittle.onboarding.ONBOARDING_STEP_LINUX
import app.whittle.onboarding.ONBOARDING_STEP_MODEL
import app.whittle.onboarding.OnboardingSteps
import app.whittle.onboarding.WelcomeScreen
import app.whittle.linux.LinuxWorkspaceScreen
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        QuickStart.from(intent.action, intent.component?.className)?.let { quickStart.value = it }
        if (intent.action == ACTION_SHOW_CONNECTIONS) openingConnections.value = true
        if (isAssist(intent)) assisting.value = true
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
                                        connect.state.collectAsStateWithLifecycle().value,
                                        connect,
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
                            val toolsConnection = toolsOf?.let { id -> connections.firstOrNull { it.server.id == id } }
                            val opening = files
                            if (opening != null) {
                                FilesBrowser(graph, opening, onAsk = chat::sendText, onClose = { files = null })
                            } else if (showingAutomations) {
                                LaunchedEffect(Unit) { chat.refreshProjects() }
                                AutomationsScreen(
                                    graph,
                                    projects = state.projects.map { it.name },
                                    onBack = { showingAutomations = false; showingSettings = true },
                                    onRunNow = { automation ->
                                        scope.launch {
                                            when ((application as WhittleApplication).automationRunner.runNow(automation.id)) {
                                                RunNowResult.STARTED -> { showingAutomations = false; showingSettings = false }
                                                RunNowResult.BUSY -> Toast.makeText(this@MainActivity, R.string.automation_busy, Toast.LENGTH_SHORT).show()
                                                RunNowResult.NO_MODEL -> Toast.makeText(this@MainActivity, R.string.automation_no_model, Toast.LENGTH_SHORT).show()
                                                RunNowResult.NOT_FOUND -> Unit
                                            }
                                        }
                                    },
                                    onOpenChat = { id -> chat.openChat(id); showingAutomations = false; showingSettings = false },
                                )
                            } else if (showingMemory) {
                                MemoryScreen(graph, onBack = { showingMemory = false; showingSettings = true })
                            } else if (showingLinux) {
                                LinuxWorkspaceScreen(graph, onBack = { showingLinux = false; showingSettings = true })
                            } else if (showingConnectionLibrary) {
                                ConnectionLibraryScreen(graph, onBack = { showingConnectionLibrary = false; showingSettings = true })
                            } else if (toolsConnection != null) {
                                ConnectionToolsScreen(graph, toolsConnection, onBack = { toolsOf = null; showingSettings = true })
                            } else if (showingModelPicker && choosingProvider) {
                                val claudeCodeInUse = graph.claudeCode.runs(state.model) && pickerProvider == null
                                val shownProvider = pickerProvider ?: state.model?.providerId?.takeUnless { claudeCodeInUse }
                                BringModelScreen(
                                    providers = graph.providers.providers,
                                    selected = shownProvider,
                                    claudeCode = ClaudeCodeChoice(graph.claudeCode.config.displayName, claudeCodeInUse).takeIf { graph.claudeCode.config.enabled },
                                    onProvider = { id ->
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
                                    state = picker.state.collectAsStateWithLifecycle().value,
                                    viewModel = picker,
                                    onClose = { showingModelPicker = false; showingSettings = !pickerFromChat; pickerFromChat = false; pickerProvider = null },
                                    onUseAnother = { choosingProvider = true },
                                )
                            } else if (showingSettings) {
                                SettingsScreen(
                                    graph = graph,
                                    settings = settings,
                                    model = state.model,
                                    onBack = { showingSettings = false },
                                    onChangeModel = { showingSettings = false; pickerOpenings += 1; showingModelPicker = true },
                                    onDeleteAllChats = chat::deleteAllChats,
                                    onBrowseConnections = { showingSettings = false; showingConnectionLibrary = true },
                                    onConnectionTools = { connection -> showingSettings = false; toolsOf = connection.server.id },
                                    onMemory = { showingSettings = false; showingMemory = true },
                                    onAutomations = { showingSettings = false; showingAutomations = true },
                                    onLinux = { showingSettings = false; showingLinux = true },
                                )
                            } else if (showingAllChats) {
                                BackHandler { showingAllChats = false }
                                AllChatsScreen(
                                    state = state,
                                    viewModel = chat,
                                    onBack = { showingAllChats = false },
                                    onOpen = { id -> chat.openChat(id); showingAllChats = false },
                                )
                            } else {
                                ChatScreen(
                                    state = state,
                                    viewModel = chat,
                                    sidebarMaxChats = graph.sidebarMaxChats,
                                    voiceDefaults = graph.voiceDefaults,
                                    previewHeight = graph.previewSheetHeight,
                                    onViewAllChats = { showingAllChats = true },
                                    onOpenSettings = { showingSettings = true },
                                    avatarSeed = settings?.avatarSeed,
                                    aboveComposer = { LinuxProgressStrip(graph, compact = true) },
                                    onOpenProjects = { files = FilesOpening(FilesPlace.Folder("")) },
                                    onOpenFiles = { files = it },
                                    recentProjects = graph.projectDefaults.recentCount,
                                    onChangeModel = { pickerFromChat = true; pickerOpenings += 1; showingModelPicker = true },
                                )
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
private fun ComponentActivity.drawBehindSystemBars(dark: Boolean) {
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
