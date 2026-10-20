package app.whittle

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = (application as WhittleApplication).graph
        setContent {
            // Until saved settings load, the bundled defaults apply; a changed setting restyles at once.
            val settings by graph.currentSettings.collectAsStateWithLifecycle()
            val appearance = settings?.let { WhittleAppearance(wallpaperColors = it.wallpaperColors, haptics = it.haptics) } ?: graph.defaultAppearance
            WhittleTheme(appearance) {
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
                            var fullScreenArtifact by remember { mutableStateOf<ArtifactRef?>(null) }
                            var showingSettings by rememberSaveable { mutableStateOf(false) }
                            val artifact = fullScreenArtifact
                            if (showingSettings) {
                                SettingsScreen(
                                    graph = graph,
                                    settings = settings,
                                    model = state.model,
                                    onBack = { showingSettings = false },
                                    onChangeModel = { showingSettings = false; chat.changeModel() },
                                    onDeleteAllChats = chat::deleteAllChats,
                                )
                            } else if (artifact != null) {
                                ArtifactScreen(artifact, graph.artifacts, state.workspaceRevision, onBack = { fullScreenArtifact = null })
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
                                    onViewAllChats = { showingAllChats = true },
                                    onOpenArtifactFullScreen = { fullScreenArtifact = it },
                                    onOpenSettings = { showingSettings = true },
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
