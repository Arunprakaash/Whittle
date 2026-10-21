package app.whittle.settings

import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Row
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.connectors.mcp.ConnectorState
import app.whittle.connectors.mcp.HeaderSpec
import app.whittle.connectors.mcp.McpAuthMode
import app.whittle.connectors.mcp.McpConnection
import app.whittle.core.common.Outcome
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.observability.posthog.hiddenFromReplay
import kotlinx.coroutines.launch

/** Services the user connects: Supabase and their own MCP servers, with the library and an address to add one. */
@Composable
fun ConnectionsSection(graph: AppGraph, onBrowse: () -> Unit, onChooseTools: (McpConnection) -> Unit) {
    val connectors = graph.connectors
    val connections by connectors.all.collectAsState()
    val context = LocalContext.current
    var managing by remember { mutableStateOf<McpConnection?>(null) }
    var askingKeys by remember { mutableStateOf<McpConnection?>(null) }
    var adding by remember { mutableStateOf(false) }
    ConnectorFailures(graph)
    SettingsSection(stringResource(R.string.settings_section_connections)) {
        val count = connections.size + 2
        connections.forEachIndexed { index, connection ->
            val state by connection.state.collectAsState()
            val connected = state is ConnectorState.Connected
            val connect = {
                if (connection.server.auth == McpAuthMode.TOKEN) askingKeys = connection else connectors.connect(context, connection)
            }
            SettingsRow(
                icon = connection.server.icon,
                logoHost = connectors.logoHost(connection),
                title = connection.server.name,
                subtitle = when {
                    connected -> stringResource(R.string.connector_connected)
                    state == ConnectorState.Connecting -> stringResource(R.string.connector_connecting)
                    connection.server.auth == McpAuthMode.TOKEN -> stringResource(R.string.connector_needs_token)
                    else -> null
                },
                shape = segmentedShape(index, count),
                // Services the user added open their details (rename, tools, remove) even before they connect.
                onClick = { if (connected || connectors.isCustom(connection)) managing = connection else connect() },
                trailing = if (connected || state == ConnectorState.Connecting) null else {
                    { TextButton(onClick = connect) { Text(stringResource(R.string.connector_connect)) } }
                },
            )
        }
        SettingsRow(
            icon = "travel_explore",
            title = stringResource(R.string.connector_browse),
            subtitle = null,
            shape = segmentedShape(count - 2, count),
            onClick = onBrowse,
        )
        SettingsRow(
            icon = "add",
            title = stringResource(R.string.connector_add),
            subtitle = null,
            shape = segmentedShape(count - 1, count),
            onClick = { adding = true },
        )
    }
    managing?.let { connection ->
        ManageDialog(
            graph = graph,
            connection = connection,
            onConnect = {
                managing = null
                if (connection.server.auth == McpAuthMode.TOKEN) askingKeys = connection else connectors.connect(context, connection)
            },
            onChooseTools = { managing = null; onChooseTools(connection) },
            onDone = { managing = null },
        )
    }
    askingKeys?.let { connection ->
        KeysDialog(connection.server.name, connection.server.headers, onDismiss = { askingKeys = null }) { values ->
            connectors.connectWithHeaders(connection, values)
            askingKeys = null
        }
    }
    if (adding) {
        AddConnectionSheet(graph, onDismiss = { adding = false }) { added ->
            adding = false
            connectAdded(graph, context, added) { askingKeys = it }
        }
    }
}

/** After a server is added: its sign-in page, its keys, or nothing when it needs no sign-in. */
internal fun connectAdded(graph: AppGraph, context: android.content.Context, added: McpConnection, askKeys: (McpConnection) -> Unit) {
    when (added.server.auth) {
        McpAuthMode.OAUTH -> graph.connectors.connect(context, added)
        McpAuthMode.TOKEN -> askKeys(added)
        McpAuthMode.NONE -> Unit // Connected while it was added.
    }
}

/**
 * A connection's details: its name (renamable when the user added it), where it lives, its tools, and the way
 * to remove or disconnect it.
 */
@Composable
private fun ManageDialog(graph: AppGraph, connection: McpConnection, onConnect: () -> Unit, onChooseTools: () -> Unit, onDone: () -> Unit) {
    val server = connection.server
    val custom = graph.connectors.isCustom(connection)
    val state by connection.state.collectAsState()
    val connected = state is ConnectorState.Connected
    var name by remember(connection) { mutableStateOf(server.name) }
    var confirmingRemove by remember(connection) { mutableStateOf(false) }
    val save = { graph.connectors.rename(connection, name); onDone() }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text(stringResource(if (custom) R.string.connector_remove_title else R.string.connector_disconnect_title, server.name)) },
            text = { Text(stringResource(if (custom) R.string.connector_manage_body else R.string.connector_disconnect_body, server.name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (custom) graph.connectors.remove(connection) else graph.connectors.disconnect(connection)
                    onDone()
                }) { Text(stringResource(if (custom) R.string.connector_remove else R.string.connector_disconnect), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text(stringResource(R.string.connector_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(server.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                if (custom) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.connector_add_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Detail(stringResource(R.string.connector_detail_address), server.url)
                Detail(
                    stringResource(R.string.connector_detail_status),
                    when {
                        connected -> pluralStringResource(R.plurals.connector_tools_on, connection.tools.size, connection.tools.size, connection.offered.size)
                        state == ConnectorState.Connecting -> stringResource(R.string.connector_connecting)
                        else -> stringResource(R.string.connector_not_connected)
                    },
                )
                Detail(
                    stringResource(R.string.connector_detail_sign_in),
                    stringResource(
                        when (server.auth) {
                            McpAuthMode.OAUTH -> R.string.connector_sign_in_oauth
                            McpAuthMode.TOKEN -> R.string.connector_sign_in_key
                            McpAuthMode.NONE -> R.string.connector_sign_in_none
                        },
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    if (connected) {
                        OutlinedButton(onClick = onChooseTools) { Text(stringResource(R.string.connector_choose_tools)) }
                    } else if (state != ConnectorState.Connecting) {
                        OutlinedButton(onClick = onConnect) { Text(stringResource(R.string.connector_connect)) }
                    }
                    if (custom || connected) {
                        TextButton(onClick = { confirmingRemove = true }) {
                            Text(stringResource(if (custom) R.string.connector_remove else R.string.connector_disconnect), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = save, enabled = !custom || name.isNotBlank()) { Text(stringResource(R.string.connector_done)) } },
    )
}

/** One fact about a connection: a small label over its value. */
@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The keys a server needs: one field per header it names, or a header name and key for a server added by
 * address. Values go to the encrypted store and never into a recording.
 */
@Composable
internal fun KeysDialog(name: String, specs: List<HeaderSpec>, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>() }
    var headerName by remember { mutableStateOf(AUTHORIZATION) }
    val fields = specs.ifEmpty { null }
    val ready = if (fields != null) fields.filter { it.isRequired }.all { !values[it.name].isNullOrBlank() } && values.values.any { it.isNotBlank() }
    else headerName.isNotBlank() && !values[KEY].isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_token_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                if (fields != null) {
                    fields.forEach { spec ->
                        KeyField(
                            label = spec.name,
                            value = values[spec.name].orEmpty(),
                            secret = spec.isSecret,
                            help = spec.description,
                            onChange = { values[spec.name] = it },
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = headerName,
                        onValueChange = { headerName = it },
                        label = { Text(stringResource(R.string.connector_header_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    KeyField(stringResource(R.string.connector_token_label), values[KEY].orEmpty(), secret = true, help = null) { values[KEY] = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = { onSave(if (fields != null) values.filterValues { it.isNotBlank() } else mapOf(headerName.trim() to values[KEY].orEmpty())) },
            ) { Text(stringResource(R.string.connector_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.connector_cancel)) } },
    )
}

@Composable
private fun KeyField(label: String, value: String, secret: Boolean, help: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().hiddenFromReplay(),
    )
}

/** Says why connecting a service failed, while Settings is open. */
@Composable
internal fun ConnectorFailures(graph: AppGraph) {
    val context = LocalContext.current
    LaunchedEffect(graph) {
        graph.connectors.failures.collect { reason ->
            Toast.makeText(context, context.getString(R.string.connector_failed, reason), Toast.LENGTH_LONG).show()
        }
    }
}

private const val AUTHORIZATION = "Authorization"
private const val KEY = "key"
