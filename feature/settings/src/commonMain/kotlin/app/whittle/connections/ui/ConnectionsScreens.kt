package app.whittle.connections.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.design.components.ReadingLazyColumn
import app.whittle.design.components.CopyableText
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.design.components.SiteIcon
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleSearchBar
import app.whittle.design.components.connectedShape
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.window.BackHandler
import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.WindowTopBar
import app.whittle.design.window.plainTextClip
import app.whittle.design.window.secretContent
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Services the user connects: Supabase and their own MCP servers, with the library and an address to add one. */
@Composable
fun ConnectionsSection(host: ConnectionsHost, platform: ConnectionsPlatform, onBrowse: () -> Unit, onChooseTools: (String) -> Unit) {
    val state by host.state.collectAsState()
    // Ones the user added, and ones Whittle comes with once they're connected; the rest wait in the library.
    val connections = state.connections.filter { it.custom || it.status != ConnectionStatus.Disconnected }
    var managing by remember { mutableStateOf<String?>(null) }
    var askingKeys by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val connect = rememberConnect(host, platform) { askingKeys = it.id }
    ConnectorFailures(host, platform)
    SettingsSection(stringResource(Res.string.settings_section_connections)) {
        val count = connections.size + 2
        connections.forEachIndexed { index, connection ->
            val connected = connection.status == ConnectionStatus.Connected
            SettingsRow(
                icon = connection.icon,
                logoHost = connection.logoHost,
                title = connection.name,
                subtitle = when {
                    connected && !connection.reachable -> stringResource(Res.string.connector_unreachable)
                    connected -> stringResource(Res.string.connector_connected)
                    connection.status == ConnectionStatus.Connecting -> stringResource(Res.string.connector_connecting)
                    connection.status == ConnectionStatus.SignInExpired -> stringResource(Res.string.connector_signed_out)
                    connection.auth == ConnectionAuth.Key -> stringResource(Res.string.connector_needs_token)
                    else -> null
                },
                shape = segmentedShape(index, count),
                // Services the user added open their details (rename, tools, remove) even before they connect.
                onClick = { if (connected || connection.custom || connection.status == ConnectionStatus.SignInExpired) managing = connection.id else connect(connection) },
                trailing = if (connected || connection.status == ConnectionStatus.Connecting) null else {
                    { TextButton(onClick = { connect(connection) }) { Text(stringResource(connectLabel(connection.status))) } }
                },
            )
        }
        SettingsRow(icon = "travel_explore", title = stringResource(Res.string.connector_browse), subtitle = null, shape = segmentedShape(count - 2, count), onClick = onBrowse)
        SettingsRow(icon = "add", title = stringResource(Res.string.connector_add), subtitle = null, shape = segmentedShape(count - 1, count), onClick = { adding = true })
    }
    managing?.let { id ->
        state.connections.firstOrNull { it.id == id }?.let { connection ->
            ManageDialog(
                host = host,
                connection = connection,
                onConnect = { managing = null; connect(connection) },
                onChooseTools = { managing = null; onChooseTools(id) },
                onDone = { managing = null },
            )
        } ?: run { managing = null }
    }
    askingKeys?.let { id -> KeysFor(host, state, id, onDone = { askingKeys = null }) }
    if (adding) {
        AddConnectionSheet(host, onDismiss = { adding = false }) { added ->
            adding = false
            afterAdding(host, platform, added, askKeys = { askingKeys = it })
        }
    }
}

/**
 * How a connection connects from here: its keys in a dialog, or the service's own sign-in page, which only the phone
 * can open; elsewhere it says to sign in on the phone.
 */
@Composable
private fun rememberConnect(host: ConnectionsHost, platform: ConnectionsPlatform, askKeys: (ConnectionEntry) -> Unit): (ConnectionEntry) -> Unit {
    val onPhone = stringResource(Res.string.connector_sign_in_on_phone)
    return { connection ->
        when {
            connection.auth == ConnectionAuth.Key -> askKeys(connection)
            platform.signsInHere -> host.act(ConnectionAction.Connect(connection.id))
            else -> platform.toast(onPhone.replace(NAME_SLOT, connection.name))
        }
    }
}

/** After a server is added: its sign-in page, its keys, or nothing when it needs no sign-in. */
private fun afterAdding(host: ConnectionsHost, platform: ConnectionsPlatform, added: ConnectionEntry, askKeys: (String) -> Unit) {
    when (added.auth) {
        ConnectionAuth.Service -> if (platform.signsInHere) host.act(ConnectionAction.Connect(added.id))
        ConnectionAuth.Key -> askKeys(added.id)
        ConnectionAuth.None -> Unit // Connected while it was added.
    }
}

/** The keys dialog for the connection with [id], once it's in the list. */
@Composable
private fun KeysFor(host: ConnectionsHost, state: ConnectionsState, id: String, onDone: () -> Unit) {
    val connection = state.connections.firstOrNull { it.id == id } ?: return
    KeysDialog(connection.name, connection.keys, onDismiss = onDone) { values ->
        host.act(ConnectionAction.ConnectWithKeys(id, values))
        onDone()
    }
}

/** Connect, or Sign in again for a connection the service signed out. */
private fun connectLabel(status: ConnectionStatus): StringResource =
    if (status == ConnectionStatus.SignInExpired) Res.string.connector_sign_in_again else Res.string.connector_connect

/**
 * A connection's details: its name (renamable when the user added it), where it lives, its tools, and the way
 * to remove or disconnect it.
 */
@Composable
private fun ManageDialog(host: ConnectionsHost, connection: ConnectionEntry, onConnect: () -> Unit, onChooseTools: () -> Unit, onDone: () -> Unit) {
    val custom = connection.custom
    val connected = connection.status == ConnectionStatus.Connected
    var name by remember(connection.id) { mutableStateOf(connection.name) }
    var confirmingRemove by remember(connection.id) { mutableStateOf(false) }
    val save = {
        if (custom && name != connection.name) host.act(ConnectionAction.Rename(connection.id, name))
        onDone()
    }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text(stringResource(if (custom) Res.string.connector_remove_title else Res.string.connector_disconnect_title, connection.name)) },
            text = { Text(stringResource(if (custom) Res.string.connector_manage_body else Res.string.connector_disconnect_body, connection.name)) },
            confirmButton = {
                TextButton(onClick = {
                    host.act(if (custom) ConnectionAction.Remove(connection.id) else ConnectionAction.Disconnect(connection.id))
                    onDone()
                }) { Text(stringResource(if (custom) Res.string.connector_remove else Res.string.connector_disconnect), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text(stringResource(Res.string.connector_cancel)) } },
        )
        return
    }
    val toolsOn = connection.tools.count { it.on }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(connection.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                if (custom) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.connector_add_name)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Detail(stringResource(Res.string.connector_detail_address), connection.url)
                Detail(
                    stringResource(Res.string.connector_detail_status),
                    when {
                        connected && !connection.reachable -> stringResource(Res.string.connector_unreachable)
                        connected -> pluralStringResource(Res.plurals.connector_tools_on, toolsOn, toolsOn, connection.tools.size)
                        connection.status == ConnectionStatus.Connecting -> stringResource(Res.string.connector_connecting)
                        connection.status == ConnectionStatus.SignInExpired -> stringResource(Res.string.connector_signed_out)
                        else -> stringResource(Res.string.connector_not_connected)
                    },
                )
                Detail(
                    stringResource(Res.string.connector_detail_sign_in),
                    stringResource(
                        when (connection.auth) {
                            ConnectionAuth.Service -> Res.string.connector_sign_in_oauth
                            ConnectionAuth.Key -> Res.string.connector_sign_in_key
                            ConnectionAuth.None -> Res.string.connector_sign_in_none
                        },
                    ),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    if (connected) {
                        OutlinedButton(onClick = onChooseTools) { Text(stringResource(Res.string.connector_choose_tools)) }
                    } else if (connection.status != ConnectionStatus.Connecting) {
                        OutlinedButton(onClick = onConnect) { Text(stringResource(connectLabel(connection.status))) }
                    }
                    // A connection the service signed out can be let go, so Whittle stops asking to sign in again.
                    if (custom || connected || connection.status == ConnectionStatus.SignInExpired) {
                        TextButton(onClick = { confirmingRemove = true }) {
                            Text(stringResource(if (custom) Res.string.connector_remove else Res.string.connector_disconnect), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = save, enabled = !custom || name.isNotBlank()) { Text(stringResource(Res.string.connector_done)) } },
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
 * address. Values go to the phone's encrypted store and never into a recording.
 */
@Composable
private fun KeysDialog(name: String, specs: List<KeyField>, onDismiss: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>() }
    var headerName by remember { mutableStateOf(AUTHORIZATION) }
    val fields = specs.ifEmpty { null }
    val ready = if (fields != null) fields.filter { it.required }.all { !values[it.name].isNullOrBlank() } && values.values.any { it.isNotBlank() }
    else headerName.isNotBlank() && !values[KEY].isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.connector_token_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                if (fields != null) {
                    fields.forEach { spec ->
                        KeyInput(label = spec.name, value = values[spec.name].orEmpty(), secret = spec.secret, help = spec.description, onChange = { values[spec.name] = it })
                    }
                } else {
                    OutlinedTextField(
                        value = headerName,
                        onValueChange = { headerName = it },
                        label = { Text(stringResource(Res.string.connector_header_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    KeyInput(stringResource(Res.string.connector_token_label), values[KEY].orEmpty(), secret = true, help = null) { values[KEY] = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = { onSave(if (fields != null) values.filterValues { it.isNotBlank() } else mapOf(headerName.trim() to values[KEY].orEmpty())) },
            ) { Text(stringResource(Res.string.connector_connect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.connector_cancel)) } },
    )
}

@Composable
private fun KeyInput(label: String, value: String, secret: Boolean, help: String?, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = help?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().secretContent(),
    )
}

/** Says why connecting a service failed, while the page is open. */
@Composable
private fun ConnectorFailures(host: ConnectionsHost, platform: ConnectionsPlatform) {
    val failed = stringResource(Res.string.connector_failed)
    LaunchedEffect(host) {
        host.failures.collect { reason -> platform.toast(failed.replace(FIRST_SLOT, reason)) }
    }
}

/** A top bar inside a computer's window: clear of its own buttons, and moving the window when dragged. */
@Composable
private fun TopBarRow(content: @Composable RowScope.() -> Unit) {
    WindowTopBar {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(LocalWindowChrome.current?.insets ?: WindowInsets(0))
                .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
        ) { content() }
    }
}

/**
 * Whittle's library of connections, grouped (work, communication, files and so on): official, hosted servers
 * picked because they sign Whittle in on their own. Search narrows it; anything else is added by its address.
 */
@Composable
fun ConnectionLibraryScreen(host: ConnectionsHost, platform: ConnectionsPlatform, onBack: () -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    val state by host.state.collectAsState()
    val scope = rememberCoroutineScope()
    val search = rememberTextFieldState()
    val query = search.text.toString().trim()
    var adding by remember { mutableStateOf<String?>(null) }
    var addingByAddress by remember { mutableStateOf(false) }
    var askingKeys by remember { mutableStateOf<String?>(null) }
    val addedLabel = stringResource(Res.string.connector_library_added)
    val failedLabel = stringResource(Res.string.connector_failed)
    val connect = rememberConnect(host, platform) { askingKeys = it.id }
    ConnectorFailures(host, platform)

    fun add(server: LibraryEntry) {
        adding = server.url
        scope.launch {
            when (val added = host.addFromLibrary(server.url)) {
                is ConnectionAdd.Added -> {
                    platform.toast(addedLabel.replace(FIRST_SLOT, server.title))
                    host.state.value.connections.firstOrNull { it.id == added.id }?.let { afterAdding(host, platform, it, askKeys = { id -> askingKeys = id }) }
                }
                is ConnectionAdd.Failed -> platform.toast(failedLabel.replace(FIRST_SLOT, added.message.orEmpty()))
            }
            adding = null
        }
    }
    val groups = state.categories.mapNotNull { category ->
        // Connections Whittle comes with sit in their group until they're connected, then live in Settings.
        val ours = state.connections.filter { !it.custom && it.category == category.id && it.matches(query) }
        val catalog = state.library.filter { it.category == category.id && it.matches(query) }
        LibraryGroup(category.id, category.title, ours, catalog).takeIf { ours.isNotEmpty() || catalog.isNotEmpty() }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopBarRow {
                TopBarButton("arrow_back", stringResource(Res.string.settings_back), onBack)
                Text(stringResource(Res.string.connector_browse), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        // On a wide window the page reads in a centred column; the whole width still scrolls.
        ReadingLazyColumn(
            minInset = WhittleSpace.l,
            modifier = Modifier.padding(padding).fillMaxSize(),
            top = WhittleSpace.m,
            bottom = WhittleSpace.m,
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            item(key = "search") {
                WhittleSearchBar(search, placeholder = stringResource(Res.string.connector_library_search), clearLabel = stringResource(Res.string.search_clear))
            }
            groups.forEach { group ->
                val size = group.ours.size + group.catalog.size
                item(key = "category:" + group.id) { SectionLabel(group.title) }
                itemsIndexed(group.ours, key = { _, it -> "ours:" + it.id }) { index, connection ->
                    LibraryRow(
                        title = connection.name,
                        description = connection.description.orEmpty(),
                        url = connection.url,
                        websiteUrl = connection.websiteUrl,
                        status = when (connection.status) {
                            ConnectionStatus.Connected -> RowStatus.Done(stringResource(Res.string.connector_connected))
                            ConnectionStatus.Connecting -> RowStatus.Busy
                            ConnectionStatus.Disconnected -> RowStatus.Action(stringResource(Res.string.connector_connect))
                            ConnectionStatus.SignInExpired -> RowStatus.Action(stringResource(Res.string.connector_sign_in_again))
                        },
                        shape = segmentedShape(index, size),
                    ) { connect(connection) }
                }
                itemsIndexed(group.catalog, key = { _, it -> "server:" + it.url }) { index, server ->
                    LibraryRow(
                        title = server.title,
                        description = server.description,
                        url = server.url,
                        websiteUrl = server.websiteUrl,
                        status = when {
                            adding == server.url -> RowStatus.Busy
                            server.added -> RowStatus.Done(stringResource(Res.string.connector_library_added_short))
                            else -> RowStatus.Action(stringResource(Res.string.connector_add_action))
                        },
                        shape = segmentedShape(group.ours.size + index, size),
                    ) { add(server) }
                }
            }
            item(key = "custom") {
                Column(Modifier.padding(top = WhittleSpace.m), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    SectionLabel(if (groups.isEmpty()) stringResource(Res.string.connector_library_none, query) else stringResource(Res.string.connector_library_other))
                    SettingsRow(
                        icon = "add",
                        title = stringResource(Res.string.connector_add),
                        subtitle = stringResource(Res.string.connector_library_other_body),
                        shape = segmentedShape(0, 1),
                        onClick = { addingByAddress = true },
                    )
                }
            }
        }
    }
    askingKeys?.let { id -> KeysFor(host, state, id, onDone = { askingKeys = null }) }
    if (addingByAddress) {
        AddConnectionSheet(host, onDismiss = { addingByAddress = false }) { added ->
            addingByAddress = false
            afterAdding(host, platform, added, askKeys = { askingKeys = it })
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
private data class LibraryGroup(val id: String, val title: String, val ours: List<ConnectionEntry>, val catalog: List<LibraryEntry>)

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
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow, shape)
            .clickable(enabled = status is RowStatus.Action, onClick = onClick)
            .padding(WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        SiteIcon(host = hostOf(websiteUrl ?: url), containerSize = WhittleSize.Tile.s, iconSize = WhittleSize.Icon.m) {
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
            Text(hostOf(url), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
        when (status) {
            RowStatus.Busy -> CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline)
            is RowStatus.Done -> Text(status.label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            is RowStatus.Action -> Text(status.label, style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

/** The tools the connection with [id] offers, each switchable: the agent only sees the ones left on. */
@Composable
fun ConnectionToolsScreen(host: ConnectionsHost, id: String, onBack: () -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    val state by host.state.collectAsState()
    val connection = state.connections.firstOrNull { it.id == id }
    val offered = connection?.tools.orEmpty().sortedBy { it.name }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopBarRow {
                TopBarButton("arrow_back", stringResource(Res.string.settings_back), onBack)
                Text(stringResource(Res.string.connector_tools_title, connection?.name.orEmpty()), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        // On a wide window the page reads in a centred column; the whole width still scrolls.
        ReadingLazyColumn(
            minInset = WhittleSpace.l,
            modifier = Modifier.padding(padding).fillMaxSize(),
            top = WhittleSpace.l,
            bottom = WhittleSpace.l,
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            if (offered.isEmpty()) {
                item { Text(stringResource(Res.string.connector_tools_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(offered, key = { _, tool -> tool.name }) { index, tool ->
                SettingsToggleRow(
                    icon = "build",
                    title = tool.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    subtitle = tool.description.substringBefore(". ").take(MAX_SUMMARY_CHARS).ifBlank { null },
                    checked = tool.on,
                    onCheckedChange = { on -> host.act(ConnectionAction.SetTool(id, tool.name, on)) },
                    shape = segmentedShape(index, offered.size),
                )
            }
        }
    }
}

/** How a custom connection signs in, as the sheet offers it; Automatic lets Whittle check the server. */
private enum class SignInChoice(val title: StringResource, val body: StringResource, val auth: ConnectionAuth?) {
    Automatic(Res.string.connector_mode_auto, Res.string.connector_mode_auto_body, null),
    Service(Res.string.connector_mode_oauth, Res.string.connector_mode_oauth_body, ConnectionAuth.Service),
    Key(Res.string.connector_mode_key, Res.string.connector_mode_key_body, ConnectionAuth.Key),
    None(Res.string.connector_mode_none, Res.string.connector_mode_none_body, ConnectionAuth.None),
}

/** The connection type; Automatic goes by the address and falls back to SSE. */
private enum class TransportChoice(val label: StringResource, val kind: ConnectionTransport?) {
    Automatic(Res.string.connector_transport_auto, null),
    Http(Res.string.connector_transport_http, ConnectionTransport.StreamableHttp),
    Sse(Res.string.connector_transport_sse, ConnectionTransport.Sse),
}

/** One header the user types for a key-signed server. */
private class HeaderEntry(name: String) {
    var name by mutableStateOf(name)
    var value by mutableStateOf("")
    var shown by mutableStateOf(false)
}

/**
 * Adds any MCP server: its name and address, how it signs in, and, under Advanced, a client the user
 * registered with the service, scopes and the connection type. Keys typed here connect it straight away;
 * a browser sign-in starts once it's added ([onAdded]).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddConnectionSheet(host: ConnectionsHost, onDismiss: () -> Unit, onAdded: (ConnectionEntry) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var signIn by remember { mutableStateOf(SignInChoice.Automatic) }
    val headers = remember { mutableStateListOf(HeaderEntry(AUTHORIZATION)) }
    var advanced by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var secretShown by remember { mutableStateOf(false) }
    var scopes by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf(TransportChoice.Automatic) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var returnAddress by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val offersClient = signIn == SignInChoice.Automatic || signIn == SignInChoice.Service
    val failed = stringResource(Res.string.memory_save_failed)
    if (offersClient && clientId.isNotBlank()) LaunchedEffect(name) { returnAddress = host.returnAddressFor(name) }

    fun add() {
        checking = true
        error = null
        scope.launch {
            val keys = if (signIn == SignInChoice.Key) headers.filter { it.name.isNotBlank() }.associate { it.name.trim() to it.value.trim() } else emptyMap()
            val added = host.add(
                NewConnection(
                    name = name,
                    url = url,
                    auth = signIn.auth,
                    transport = transport.kind,
                    keyNames = keys.keys.toList(),
                    scopes = if (offersClient) scopes.split(WHITESPACE).filter { it.isNotBlank() } else emptyList(),
                    clientId = clientId.trim().takeIf { offersClient && it.isNotEmpty() },
                    clientSecret = clientSecret.trim().takeIf { offersClient && clientId.isNotBlank() && it.isNotEmpty() },
                ),
                keys = keys.filterValues { it.isNotEmpty() },
            )
            when (added) {
                is ConnectionAdd.Added -> if (keys.values.any { it.isNotEmpty() }) {
                    onDismiss()
                } else {
                    host.state.value.connections.firstOrNull { it.id == added.id }?.let(onAdded) ?: onDismiss()
                }
                is ConnectionAdd.Failed -> error = added.message ?: failed
            }
            checking = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!checking) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = WhittleSpace.xxl),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
                    Text(stringResource(Res.string.connector_sheet_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(Res.string.connector_sheet_intro), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.connector_add_name)) },
                    singleLine = true,
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text(stringResource(Res.string.connector_add_url)) },
                    singleLine = true,
                    enabled = !checking,
                    isError = error != null,
                    supportingText = { Text(error ?: stringResource(Res.string.connector_add_url_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )

                SectionTitle(stringResource(Res.string.connector_sign_in_label))
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                    SignInChoice.entries.forEachIndexed { index, choice ->
                        ChoiceRow(choice, selected = choice == signIn, index = index) {
                            haptics.perform(WhittleHaptic.Select)
                            signIn = choice
                        }
                    }
                }

                if (signIn == SignInChoice.Key) {
                    headers.forEachIndexed { index, header ->
                        HeaderCard(header, index, removable = index > 0, enabled = !checking) { headers.remove(header) }
                    }
                    TextButton(onClick = { headers += HeaderEntry("") }, enabled = !checking) {
                        WhittleIcon("add", size = WhittleSize.Icon.m)
                        Text(stringResource(Res.string.connector_header_add), Modifier.padding(start = WhittleSpace.s))
                    }
                }

                AdvancedRow(
                    summary = stringResource(if (offersClient) Res.string.connector_advanced_summary_client else Res.string.connector_advanced_summary_transport),
                    open = advanced,
                ) {
                    haptics.perform(WhittleHaptic.Tap)
                    advanced = !advanced
                }
                AnimatedVisibility(advanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                        if (offersClient) {
                            Text(stringResource(Res.string.connector_client_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            OutlinedTextField(
                                value = clientId,
                                onValueChange = { clientId = it },
                                label = { Text(stringResource(Res.string.connector_client_id)) },
                                singleLine = true,
                                enabled = !checking,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            SecretInput(
                                value = clientSecret,
                                onValueChange = { clientSecret = it },
                                label = stringResource(Res.string.connector_client_secret),
                                shown = secretShown,
                                onToggleShown = { secretShown = !secretShown },
                                enabled = !checking,
                            )
                            val address = returnAddress
                            if (clientId.isNotBlank() && address != null) {
                                CopyableText(
                                    text = address,
                                    copyDescription = stringResource(Res.string.connector_return_copy),
                                    onCopy = { scope.launch { clipboard.setClipEntry(plainTextClip(address, address)) } },
                                    label = stringResource(Res.string.connector_return_address),
                                    hint = stringResource(Res.string.connector_return_hint),
                                )
                            }
                            OutlinedTextField(
                                value = scopes,
                                onValueChange = { scopes = it },
                                label = { Text(stringResource(Res.string.connector_scopes)) },
                                supportingText = { Text(stringResource(Res.string.connector_scopes_hint)) },
                                singleLine = true,
                                enabled = !checking,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(stringResource(Res.string.connector_transport_label), style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                            TransportChoice.entries.forEachIndexed { index, choice ->
                                TransportButton(choice, selected = choice == transport, index = index, Modifier.weight(if (choice == TransportChoice.Http) WIDE_SEGMENT else 1f)) {
                                    haptics.perform(WhittleHaptic.Select)
                                    transport = choice
                                }
                            }
                        }
                        Text(stringResource(Res.string.connector_transport_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }

            // Always in reach, however far the form scrolls.
            Column(
                Modifier.fillMaxWidth().padding(horizontal = WhittleSpace.xxl, vertical = WhittleSpace.m),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    WhittleIcon("lock", size = WhittleSize.Icon.s, tint = colors.onSurfaceVariant)
                    Text(stringResource(Res.string.connector_secrets_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (checking) CircularProgressIndicator(strokeWidth = WhittleSpace.hairline, modifier = Modifier.size(WhittleSize.Icon.xl))
                    TextButton(onClick = onDismiss, enabled = !checking) { Text(stringResource(Res.string.connector_cancel)) }
                    Button(onClick = ::add, enabled = url.isNotBlank() && !checking, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(Res.string.connector_add_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One way to sign in, as a row of a segmented group: radio, title and what it means. */
@Composable
private fun ChoiceRow(choice: SignInChoice, selected: Boolean, index: Int, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.onSecondaryContainer else colors.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(segmentedShape(index, SignInChoice.entries.size))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        WhittleIcon(
            if (selected) "radio_button_checked" else "radio_button_unchecked",
            size = WhittleSize.Icon.xl,
            tint = if (selected) colors.primary else colors.outline,
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(choice.title), style = MaterialTheme.typography.titleSmall, color = content)
            Text(stringResource(choice.body), style = MaterialTheme.typography.bodySmall, color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
        }
    }
}

/** A header a key-signed server needs: its name and its value, which stays hidden unless shown. */
@Composable
private fun HeaderCard(header: HeaderEntry, index: Int, removable: Boolean, enabled: Boolean, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceContainer, RoundedCornerShape(WhittleRadius.l)).padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.connector_header_caption, index + 1), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (removable) {
                IconButton(onClick = onRemove, enabled = enabled) {
                    WhittleIcon("close", size = WhittleSize.Icon.l, contentDescription = stringResource(Res.string.connector_header_remove))
                }
            }
        }
        OutlinedTextField(
            value = header.name,
            onValueChange = { header.name = it },
            label = { Text(stringResource(Res.string.connector_header_name)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        val isAuthorization = header.name.trim().equals(AUTHORIZATION, ignoreCase = true)
        SecretInput(
            value = header.value,
            onValueChange = { header.value = it },
            label = stringResource(if (isAuthorization) Res.string.connector_header_key else Res.string.connector_header_value),
            shown = header.shown,
            onToggleShown = { header.shown = !header.shown },
            enabled = enabled,
        )
        if (isAuthorization) {
            Text(stringResource(Res.string.connector_header_bearer_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

/** A key or secret: masked unless the user shows it, and never in session recordings. */
@Composable
private fun SecretInput(value: String, onValueChange: (String) -> Unit, label: String, shown: Boolean, onToggleShown: () -> Unit, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = onToggleShown) {
                WhittleIcon(
                    if (shown) "visibility_off" else "visibility",
                    size = WhittleSize.Icon.l,
                    contentDescription = stringResource(if (shown) Res.string.connector_secret_hide else Res.string.connector_secret_show),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().secretContent(),
    )
}

/** Opens and closes the advanced settings; says what's inside while closed. */
@Composable
private fun AdvancedRow(summary: String, open: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (open) HALF_TURN else 0f, label = "advancedChevron")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(WhittleRadius.l)).clickable(role = Role.Button, onClick = onToggle).padding(vertical = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.connector_advanced), style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        WhittleIcon("expand_more", size = WhittleSize.Icon.xl, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(turn))
    }
}

/** One of the connected connection-type buttons. */
@Composable
private fun TransportButton(choice: TransportChoice, selected: Boolean, index: Int, modifier: Modifier, onSelect: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .height(WhittleSize.Button.small)
            .clip(connectedShape(index, TransportChoice.entries.size))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHighest)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
            if (selected) WhittleIcon("check", size = WhittleSize.Icon.m, tint = colors.onSecondaryContainer)
            Text(
                stringResource(choice.label),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** The host part of an address: "mcp.example.com" from "https://mcp.example.com/sse". */
private fun hostOf(url: String): String = url.substringAfter("://", url).substringBefore('/').substringBefore('?').substringBefore(':').substringAfter('@')

private const val AUTHORIZATION = "Authorization"
private const val KEY = "key"
private const val MAX_SUMMARY_CHARS = 120
private const val HALF_TURN = 180f

/** Where a message's first value goes, for words formatted outside composition. */
private const val FIRST_SLOT = "%1\$s"
private const val NAME_SLOT = FIRST_SLOT

/** The middle button's label is longer than the others. */
private const val WIDE_SEGMENT = 1.5f
private val WHITESPACE = Regex("\\s+")
