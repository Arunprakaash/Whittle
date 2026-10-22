package app.whittle

import app.whittle.settings.MemoryScreen
import android.widget.Toast
import app.whittle.automations.AutomationsScreen
import app.whittle.automations.RunNowResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import app.whittle.background.rememberNotificationPermissionRequest
import app.whittle.settings.SettingsScreen
import app.whittle.design.theme.WhittleAppearance
import app.whittle.design.components.LocalSiteIcons
import androidx.compose.runtime.CompositionLocalProvider
import android.os.Bundle
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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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
                            val connect = viewModel { ConnectViewModel(graph) }
                            ConnectScreen(connect.state.collectAsStateWithLifecycle().value, connect) {
                                LinuxProgressStrip(graph, compact = false, Modifier.padding(top = WhittleSpace.s))
                                OnboardingSteps(ONBOARDING_STEP_MODEL, ONBOARDING_STEPS, Modifier.padding(vertical = WhittleSpace.xl))
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
                            var pickerOpenings by rememberSaveable { mutableIntStateOf(0) }
                            var showingModelPicker by rememberSaveable { mutableStateOf(false) }
                            var showingConnectionLibrary by rememberSaveable { mutableStateOf(false) }
                            var showingMemory by rememberSaveable { mutableStateOf(false) }
                            var showingLinux by rememberSaveable { mutableStateOf(false) }
                            var showingAutomations by rememberSaveable { mutableStateOf(false) }
                            val scope = rememberCoroutineScope()
                            var toolsOf by rememberSaveable { mutableStateOf<String?>(null) }
                            var files by rememberSaveable(stateSaver = filesOpeningSaver) { mutableStateOf<FilesOpening?>(null) }
                            val connections by graph.connectors.all.collectAsStateWithLifecycle()
                            val toolsConnection = toolsOf?.let { id -> connections.firstOrNull { it.server.id == id } }
                            val opening = files
                            if (opening != null) {
                                FilesBrowser(graph, opening, onAsk = chat::sendText, onClose = { files = null })
                            } else if (showingAutomations) {
                                AutomationsScreen(
                                    graph,
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
                            } else if (showingModelPicker) {
                                // A fresh picker each time it opens, so its models are listed again.
                                val picker = viewModel(key = "modelPicker$pickerOpenings") { ConnectViewModel(graph, current = state.model) }
                                ModelPickerScreen(
                                    state = picker.state.collectAsStateWithLifecycle().value,
                                    viewModel = picker,
                                    onClose = { showingModelPicker = false; showingSettings = true },
                                    onSwitchProvider = { showingModelPicker = false; chat.switchProvider() },
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
