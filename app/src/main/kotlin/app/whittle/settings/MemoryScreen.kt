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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.observability.posthog.userContent
import kotlinx.coroutines.launch

/** Everything Whittle remembers about the user, each removable, and a way to forget it all. */
@Composable
fun MemoryScreen(graph: AppGraph, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val memories by graph.memory.all.collectAsState()
    var confirmingClear by remember { mutableStateOf(false) }
    // Writes go to the app's scope so leaving the screen never drops a change.
    val change = { action: suspend () -> Unit -> graph.appScope.launch { action() }; Unit }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("arrow_back", stringResource(R.string.settings_back), onBack)
                Text(stringResource(R.string.memory_title), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            if (memories.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.memory_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = WhittleSpace.m, end = WhittleSpace.m, bottom = WhittleSpace.m),
                    )
                }
            }
            itemsIndexed(memories, key = { _, memory -> memory.id }) { index, memory ->
                SettingsRow(
                    icon = "bookmarks",
                    title = memory.text,
                    subtitle = null,
                    shape = segmentedShape(index, memories.size),
                    modifier = Modifier.userContent(),
                    trailing = {
                        val label = stringResource(R.string.memory_forget)
                        IconButton(onClick = { change { graph.memory.forget(memory.id) } }) {
                            WhittleIcon("delete", contentDescription = label, size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
            }
            if (memories.isNotEmpty()) {
                item {
                    SettingsRow(
                        icon = "delete",
                        title = stringResource(R.string.memory_clear),
                        subtitle = null,
                        shape = segmentedShape(0, 1),
                        destructive = true,
                        onClick = { confirmingClear = true },
                        modifier = Modifier.padding(top = WhittleSpace.l),
                    )
                }
            }
        }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.memory_clear_title)) },
            text = { Text(stringResource(R.string.memory_clear_body)) },
            confirmButton = {
                TextButton(onClick = { confirmingClear = false; change { graph.memory.clear() } }) {
                    Text(stringResource(R.string.memory_clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(R.string.connector_cancel)) } },
        )
    }
}
