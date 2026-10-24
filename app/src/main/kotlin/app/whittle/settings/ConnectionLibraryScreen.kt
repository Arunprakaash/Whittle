package app.whittle.settings

import app.whittle.design.components.SettingsRow
import app.whittle.connectors.matches
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.connectors.mcp.LibraryServer
import app.whittle.connectors.mcp.ConnectorState
import app.whittle.connectors.mcp.McpAuthMode
import app.whittle.connectors.mcp.McpConnection
import app.whittle.core.common.Outcome
import app.whittle.design.components.SiteIcon
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleSearchBar
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whittle's library of connections, grouped (work, communication, files and so on): official, hosted servers
 * picked because they sign Whittle in on their own. Search narrows it; anything else is added by its address.
 */
@Composable
fun ConnectionLibraryScreen(graph: AppGraph, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val connectors = graph.connectors
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val connections by connectors.all.collectAsState()
    val search = rememberTextFieldState()
    val query = search.text.toString().trim()
    var adding by remember { mutableStateOf<String?>(null) }
    var addingByAddress by remember { mutableStateOf(false) }
    var askingKeys by remember { mutableStateOf<McpConnection?>(null) }
    val addedLabel = stringResource(R.string.connector_library_added)
    ConnectorFailures(graph)

    fun add(server: LibraryServer) {
        adding = server.url
        scope.launch {
            when (val added = connectors.add(server)) {
                is Outcome.Success -> {
                    Toast.makeText(context, addedLabel.format(server.title), Toast.LENGTH_SHORT).show()
                    connectAdded(graph, context, added.value) { askingKeys = it }
                }
                is Outcome.Failure -> Toast.makeText(context, resources.getString(R.string.connector_failed, added.error.message), Toast.LENGTH_LONG).show()
            }
            adding = null
        }
    }
    val groups = connectors.categories.mapNotNull { category ->
        // Connections Whittle comes with sit in their group until they're connected, then live in Settings.
        val ours = connections.filter { !connectors.isCustom(it) && it.server.category == category.id && it.server.matches(query) }
        val catalog = connectors.library.filter { it.category == category.id && it.matches(query) }
        LibraryGroup(category.id, category.title, ours, catalog).takeIf { ours.isNotEmpty() || catalog.isNotEmpty() }
    }
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
            item(key = "search") {
                WhittleSearchBar(search, placeholder = stringResource(R.string.connector_library_search), clearLabel = stringResource(R.string.search_clear))
            }
            groups.forEach { group ->
                val size = group.ours.size + group.catalog.size
                item(key = "category:" + group.id) { SectionLabel(group.title) }
                itemsIndexed(group.ours, key = { _, it -> "ours:" + it.server.id }) { index, connection ->
                    val state by connection.state.collectAsState()
                    LibraryRow(
                        title = connection.server.name,
                        description = connection.server.description.orEmpty(),
                        url = connection.server.url,
                        websiteUrl = connection.server.websiteUrl,
                        status = when (state) {
                            is ConnectorState.Connected -> RowStatus.Done(stringResource(R.string.connector_connected))
                            ConnectorState.Connecting -> RowStatus.Busy
                            ConnectorState.Disconnected -> RowStatus.Action(stringResource(R.string.connector_connect))
                            ConnectorState.SignInExpired -> RowStatus.Action(stringResource(R.string.connector_sign_in_again))
                        },
                        shape = segmentedShape(index, size),
                    ) {
                        if (connection.server.auth == McpAuthMode.TOKEN) askingKeys = connection else connectors.connect(context, connection)
                    }
                }
                itemsIndexed(group.catalog, key = { _, it -> "server:" + it.url }) { index, server ->
                    // Reading the connections keeps Added current as services come and go.
                    val added = connections.isNotEmpty() && connectors.added(server.url) != null
                    LibraryRow(
                        title = server.title,
                        description = server.description,
                        url = server.url,
                        websiteUrl = server.websiteUrl,
                        status = when {
                            adding == server.url -> RowStatus.Busy
                            added -> RowStatus.Done(stringResource(R.string.connector_library_added_short))
                            else -> RowStatus.Action(stringResource(R.string.connector_add_action))
                        },
                        shape = segmentedShape(group.ours.size + index, size),
                    ) { add(server) }
                }
            }
            item(key = "custom") {
                Column(Modifier.padding(top = WhittleSpace.m), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    SectionLabel(stringResource(if (groups.isEmpty()) R.string.connector_library_none else R.string.connector_library_other, query))
                    SettingsRow(
                        icon = "add",
                        title = stringResource(R.string.connector_add),
                        subtitle = stringResource(R.string.connector_library_other_body),
                        shape = segmentedShape(0, 1),
                        onClick = { addingByAddress = true },
                    )
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
    if (addingByAddress) {
        AddConnectionSheet(graph, onDismiss = { addingByAddress = false }) { added ->
            addingByAddress = false
            connectAdded(graph, context, added) { askingKeys = it }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = WhittleSpace.m, bottom = WhittleSpace.s, start = WhittleSpace.m),
    )
}

/** A group of the library: the connections Whittle comes with first, then the ones it can add. */
private data class LibraryGroup(val id: String, val title: String, val ours: List<McpConnection>, val catalog: List<LibraryServer>)

/** What a library row ends with: working on it, done (Added, Connected), or the action to take. */
private sealed interface RowStatus {
    data object Busy : RowStatus

    data class Done(val label: String) : RowStatus

    data class Action(val label: String) : RowStatus
}

/** One server: its site's icon, name, what it does, its address, and its [status]. */
@Composable
private fun LibraryRow(title: String, description: String, url: String, websiteUrl: String?, status: RowStatus, shape: Shape, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val host = (websiteUrl ?: url).toHttpUrlOrNull()?.host.orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow, shape)
            .clickable(enabled = status is RowStatus.Action, onClick = onClick)
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
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description.isNotBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(url.toHttpUrlOrNull()?.host.orEmpty(), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
        when (status) {
            RowStatus.Busy -> CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline)
            is RowStatus.Done -> Text(status.label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            is RowStatus.Action -> Text(status.label, style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}
