package app.whittle

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
import app.whittle.chat.ArtifactsScreen
import androidx.compose.runtime.LaunchedEffect
import app.whittle.background.rememberNotificationPermissionRequest
import app.whittle.settings.SettingsScreen
import app.whittle.design.theme.WhittleAppearance
import app.whittle.artifacts.ArtifactScreen
import app.whittle.artifacts.ArtifactRef
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
                val gate by remember { graph.connections.model.map { if (it == null) Gate.Connect else Gate.Chat } }
                    .collectAsStateWithLifecycle(Gate.Loading)
                // Surface sets the content color, so text on every screen follows the theme in light and dark.
                CompositionLocalProvider(LocalSiteIcons provides graph.siteIcons) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    when (gate) {
                        Gate.Loading -> Unit
                        Gate.Connect -> {
                            val connect = viewModel { ConnectViewModel(graph) }
                            ConnectScreen(connect.state.collectAsStateWithLifecycle().value, connect)
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
                            var showingArtifacts by rememberSaveable { mutableStateOf(false) }
                            var fullScreenArtifact by remember { mutableStateOf<ArtifactRef?>(null) }
                            var showingSettings by rememberSaveable { mutableStateOf(false) }
                            var pickerOpenings by rememberSaveable { mutableIntStateOf(0) }
                            var showingModelPicker by rememberSaveable { mutableStateOf(false) }
                            var showingConnectionLibrary by rememberSaveable { mutableStateOf(false) }
                            var toolsOf by rememberSaveable { mutableStateOf<String?>(null) }
                            val connections by graph.connectors.all.collectAsStateWithLifecycle()
                            val toolsConnection = toolsOf?.let { id -> connections.firstOrNull { it.server.id == id } }
                            val artifact = fullScreenArtifact
                            if (showingConnectionLibrary) {
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
                                )
                            } else if (artifact != null) {
                                ArtifactScreen(artifact, graph.artifacts, state.workspaceRevision, onBack = { fullScreenArtifact = null })
                            } else if (showingArtifacts) {
                                ArtifactsScreen(
                                    artifacts = state.artifacts,
                                    onBack = { showingArtifacts = false },
                                    onOpen = { fullScreenArtifact = it },
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
                                    artifacts = graph.artifacts,
                                    artifactSheetHeight = graph.artifactSheetHeight,
                                    voiceDefaults = graph.voiceDefaults,
                                    onViewAllChats = { showingAllChats = true },
                                    onOpenArtifactFullScreen = { fullScreenArtifact = it },
                                    onOpenSettings = { showingSettings = true },
                                    onOpenArtifacts = { showingArtifacts = true },
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }

    /** Whether a model is connected yet; Loading avoids flashing the connect screen at launch. */
    private enum class Gate { Loading, Connect, Chat }
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
    // As enableEdgeToEdge does for a transparent, theme-following bar: the system keeps 3-button navigation legible.
    window.isNavigationBarContrastEnforced = true
    window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    WindowInsetsControllerCompat(window, window.decorView).run {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}
