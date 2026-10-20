package app.whittle.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.connectors.mcp.McpConnection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleSpace

/** The tools a connection offers, each switchable: the agent only sees the ones left on. */
@Composable
fun ConnectionToolsScreen(graph: AppGraph, connection: McpConnection, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val offered = connection.offered.sortedBy { it.name }
    var off by remember { mutableStateOf(connection.excludedTools) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("arrow_back", stringResource(R.string.settings_back), onBack)
                Text(stringResource(R.string.connector_tools_title, connection.server.name), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            if (offered.isEmpty()) {
                item { Text(stringResource(R.string.connector_tools_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(offered, key = { _, tool -> tool.name }) { index, tool ->
                SettingsToggleRow(
                    icon = "build",
                    title = tool.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    subtitle = tool.description.substringBefore(". ").take(MAX_SUMMARY_CHARS).ifBlank { null },
                    checked = tool.name !in off,
                    onCheckedChange = { on ->
                        off = if (on) off - tool.name else off + tool.name
                        graph.connectors.setExcludedTools(connection, off)
                    },
                    shape = segmentedShape(index, offered.size),
                )
            }
        }
    }
}

private const val MAX_SUMMARY_CHARS = 120
