package app.whittle.settings

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
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.connectors.mcp.ConnectorState
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

/** Services the user connects: Supabase and their own MCP servers, with a row to add one. */
@Composable
fun ConnectionsSection(graph: AppGraph) {
    val connectors = graph.connectors
    val connections by connectors.all.collectAsState()
    val context = LocalContext.current
    var managing by remember { mutableStateOf<McpConnection?>(null) }
    var askingToken by remember { mutableStateOf<McpConnection?>(null) }
    var adding by remember { mutableStateOf(false) }
    ConnectorFailures(graph)
    SettingsSection(stringResource(R.string.settings_section_connections)) {
        val count = connections.size + 1
        connections.forEachIndexed { index, connection ->
            val state by connection.state.collectAsState()
            val connected = state is ConnectorState.Connected
            SettingsRow(
                icon = connection.server.icon,
                title = connection.server.name,
                subtitle = when {
                    connected -> stringResource(R.string.connector_connected)
                    state == ConnectorState.Connecting -> stringResource(R.string.connector_connecting)
                    connection.server.auth == McpAuthMode.TOKEN -> stringResource(R.string.connector_needs_token)
                    else -> null
                },
                shape = segmentedShape(index, count),
                onClick = {
                    when {
                        connected || connectors.isCustom(connection) && connection.server.auth == McpAuthMode.NONE -> managing = connection
                        connection.server.auth == McpAuthMode.TOKEN -> askingToken = connection
                        else -> connectors.connect(context, connection)
                    }
                },
                trailing = if (connected) null else {
                    { Text(stringResource(R.string.connector_connect), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                },
            )
        }
        SettingsRow(
            icon = "add",
            title = stringResource(R.string.connector_add),
            subtitle = null,
            shape = segmentedShape(count - 1, count),
            onClick = { adding = true },
        )
    }
    managing?.let { connection -> ManageDialog(graph, connection, onDone = { managing = null }) }
    askingToken?.let { connection ->
        TokenDialog(connection.server.name, onDismiss = { askingToken = null }) { token ->
            connectors.connectWithToken(connection, token)
            askingToken = null
        }
    }
    if (adding) {
        AddDialog(graph, onDismiss = { adding = false }) { added ->
            adding = false
            when (added.server.auth) {
                McpAuthMode.OAUTH -> connectors.connect(context, added)
                McpAuthMode.TOKEN -> askingToken = added
                McpAuthMode.NONE -> Unit // Connected while it was added.
            }
        }
    }
}

@Composable
private fun ManageDialog(graph: AppGraph, connection: McpConnection, onDone: () -> Unit) {
    val name = connection.server.name
    val custom = graph.connectors.isCustom(connection)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(if (custom) R.string.connector_manage_title else R.string.connector_disconnect_title, name)) },
        text = { Text(stringResource(if (custom) R.string.connector_manage_body else R.string.connector_disconnect_body, name)) },
        confirmButton = {
            if (custom) {
                TextButton(onClick = { graph.connectors.remove(connection); onDone() }) { Text(stringResource(R.string.connector_remove)) }
            } else {
                TextButton(onClick = { graph.connectors.disconnect(connection); onDone() }) { Text(stringResource(R.string.connector_disconnect)) }
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.connector_cancel)) } },
    )
}

/** The access token a server needs, pasted once; it goes to the encrypted store and never into a recording. */
@Composable
private fun TokenDialog(name: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.connector_token_title, name)) },
        text = {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.connector_token_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().hiddenFromReplay(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(token) }, enabled = token.isNotBlank()) { Text(stringResource(R.string.connector_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.connector_cancel)) } },
    )
}

/** Name and address of an MCP server; Whittle works out how it signs in. */
@Composable
private fun AddDialog(graph: AppGraph, onDismiss: () -> Unit, onAdded: (McpConnection) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = { Text(stringResource(R.string.connector_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.connector_add_name)) },
                    singleLine = true,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text(stringResource(R.string.connector_add_url)) },
                    singleLine = true,
                    enabled = !checking,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (checking) CircularProgressIndicator(strokeWidth = WhittleSpace.hairline, modifier = Modifier.size(WhittleSize.Icon.xl))
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        when (val added = graph.connectors.add(name, url)) {
                            is Outcome.Success -> onAdded(added.value)
                            is Outcome.Failure -> error = added.error.message
                        }
                        checking = false
                    }
                },
            ) { Text(stringResource(R.string.connector_add_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !checking) { Text(stringResource(R.string.connector_cancel)) } },
    )
}

/** Says why connecting a service failed, while Settings is open. */
@Composable
private fun ConnectorFailures(graph: AppGraph) {
    val context = LocalContext.current
    LaunchedEffect(graph) {
        graph.connectors.failures.collect { reason ->
            Toast.makeText(context, context.getString(R.string.connector_failed, reason), Toast.LENGTH_LONG).show()
        }
    }
}
