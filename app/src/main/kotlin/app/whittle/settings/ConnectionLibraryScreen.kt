package app.whittle.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.connectors.mcp.LibraryServer
import app.whittle.connectors.mcp.McpConnection
import app.whittle.core.common.Outcome
import app.whittle.design.components.SiteIcon
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleSearchBar
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * MCP servers to add: Whittle's short featured list, and a search over the MCP Registry, which anyone can
 * publish to, so its results are labelled as unreviewed and only come up when the user searches. Adding signs
 * in the way the server needs.
 */
@Composable
fun ConnectionLibraryScreen(graph: AppGraph, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val connectors = graph.connectors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connections by connectors.all.collectAsState()
    val search = rememberTextFieldState()
    var query by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<LibraryServer>() }
    var next by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<String?>(null) }
    var askingKeys by remember { mutableStateOf<McpConnection?>(null) }
    val addedLabel = stringResource(R.string.connector_library_added)
    ConnectorFailures(graph)

    suspend fun load(cursor: String?) {
        loading = true
        failed = null
        when (val page = connectors.registry.search(query, cursor)) {
            is Outcome.Success -> {
                if (cursor == null) results.clear()
                // The featured list already shows these; the registry often has copies.
                val featuredUrls = connectors.featured.map { it.url }.toSet()
                results += page.value.servers.filter { it.url !in featuredUrls && results.none { known -> known.url == it.url } }
                next = page.value.next
            }
            is Outcome.Failure -> failed = page.error.message
        }
        loading = false
    }
    LaunchedEffect(search) {
        snapshotFlow { search.text.toString() }.collectLatest { text ->
            delay(SEARCH_PAUSE_MILLIS)
            query = text
            // Old results would read as answers to the new search while it loads.
            results.clear()
            next = null
            failed = null
            // The registry is long and unreviewed: it answers searches, it doesn't fill the page.
            if (text.isNotBlank()) load(cursor = null)
        }
    }
    fun add(server: LibraryServer) {
        adding = server.url
        scope.launch {
            when (val added = connectors.add(server)) {
                is Outcome.Success -> {
                    Toast.makeText(context, addedLabel.format(server.title), Toast.LENGTH_SHORT).show()
                    connectAdded(graph, context, added.value) { askingKeys = it }
                }
                is Outcome.Failure -> Toast.makeText(context, context.getString(R.string.connector_failed, added.error.message), Toast.LENGTH_LONG).show()
            }
            adding = null
        }
    }
    val featured = connectors.featured.filter { query.isBlank() || query.lowercase() in (it.title + " " + it.description).lowercase() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("arrow_back", stringResource(R.string.settings_back), onBack)
                Text(stringResource(R.string.connector_browse), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            item {
                WhittleSearchBar(search, placeholder = stringResource(R.string.connector_library_search), clearLabel = stringResource(R.string.search_clear))
            }
            if (featured.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.connector_library_featured)) }
                itemsIndexed(featured, key = { _, it -> "featured:" + it.url }) { index, server ->
                    LibraryRow(server, connectors.added(server.url) != null && connections.isNotEmpty(), adding == server.url, segmentedShape(index, featured.size)) { add(server) }
                }
            }
            item {
                Column(Modifier.padding(top = WhittleSpace.l, bottom = WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
                    SectionLabel(stringResource(R.string.connector_library_registry))
                    Text(
                        stringResource(if (query.isBlank()) R.string.connector_library_registry_hint else R.string.connector_library_registry_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(results, key = { _, it -> "registry:" + it.url }) { index, server ->
                LibraryRow(server, connectors.added(server.url) != null, adding == server.url, segmentedShape(index, results.size)) { add(server) }
            }
            if (query.isNotBlank()) item {
                Row(Modifier.fillMaxWidth().padding(vertical = WhittleSpace.m), horizontalArrangement = Arrangement.Center) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(WhittleSize.Icon.xl), strokeWidth = WhittleSpace.hairline)
                        failed != null -> TextButton(onClick = { scope.launch { load(cursor = null) } }) { Text(stringResource(R.string.connector_library_retry)) }
                        results.isEmpty() -> Text(stringResource(R.string.connector_library_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        next != null -> TextButton(onClick = { scope.launch { load(next) } }) { Text(stringResource(R.string.connector_library_more)) }
                    }
                }
            }
        }
    }
    askingKeys?.let { connection ->
        KeysDialog(connection.server.name, connection.server.headers, onDismiss = { askingKeys = null }) { values ->
            connectors.connectWithHeaders(connection, values)
            askingKeys = null
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = WhittleSpace.s))
}

/** One server: its site's icon, name, what it does, its address, and Add (or Added). */
@Composable
private fun LibraryRow(server: LibraryServer, added: Boolean, adding: Boolean, shape: Shape, onAdd: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val host = (server.websiteUrl ?: server.url).toHttpUrlOrNull()?.host.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow, shape)
            .clickable(enabled = !added && !adding, onClick = onAdd)
            .padding(WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        SiteIcon(host = host, containerSize = WhittleSize.Tile.s, iconSize = WhittleSize.Icon.m) {
            // Sites without an icon get the same circle, so every row lines up.
            Box(Modifier.size(WhittleSize.Tile.s).background(colors.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                WhittleIcon("extension", size = WhittleSize.Icon.m, tint = colors.onSecondaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
            Text(server.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (server.description.isNotBlank()) {
                Text(server.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(server.url.toHttpUrlOrNull()?.host.orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
        when {
            adding -> CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline)
            added -> Text(stringResource(R.string.connector_library_added_short), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            else -> Text(stringResource(R.string.connector_add_action), style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

private const val SEARCH_PAUSE_MILLIS = 350L
