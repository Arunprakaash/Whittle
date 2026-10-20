package app.whittle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.whittle.chat.ChatScreen
import app.whittle.chat.ChatViewModel
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
            WhittleTheme(graph.appearance) {
                val gate by remember { graph.connections.model.map { if (it == null) Gate.Connect else Gate.Chat } }
                    .collectAsStateWithLifecycle(Gate.Loading)
                // Surface sets the content color, so text on every screen follows the theme in light and dark.
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    when (gate) {
                        Gate.Loading -> Unit
                        Gate.Connect -> {
                            val connect = viewModel { ConnectViewModel(graph) }
                            ConnectScreen(connect.state.collectAsStateWithLifecycle().value, connect)
                        }
                        Gate.Chat -> {
                            val chat = viewModel { ChatViewModel(graph) }
                            ChatScreen(chat.state.collectAsStateWithLifecycle().value, chat)
                        }
                    }
                }
            }
        }
    }

    /** Whether a model is connected yet; Loading avoids flashing the connect screen at launch. */
    private enum class Gate { Loading, Connect, Chat }
}
