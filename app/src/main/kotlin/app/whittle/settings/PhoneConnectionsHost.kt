package app.whittle.settings

import android.content.Context
import app.whittle.AppGraph
import app.whittle.connections.ui.ConnectionAction
import app.whittle.connections.ui.ConnectionAdd
import app.whittle.connections.ui.ConnectionAuth
import app.whittle.connections.ui.ConnectionEntry
import app.whittle.connections.ui.ConnectionStatus
import app.whittle.connections.ui.ConnectionTool
import app.whittle.connections.ui.ConnectionTransport
import app.whittle.connections.ui.ConnectionsHost
import app.whittle.connections.ui.ConnectionsState
import app.whittle.connections.ui.KeyField
import app.whittle.connections.ui.LibraryCategoryEntry
import app.whittle.connections.ui.LibraryEntry
import app.whittle.connections.ui.NewConnection
import app.whittle.connectors.mcp.ConnectorState
import app.whittle.connectors.mcp.HeaderSpec
import app.whittle.connectors.mcp.McpAuthMode
import app.whittle.connectors.mcp.McpConnection
import app.whittle.connectors.mcp.McpTransportKind
import app.whittle.connectors.mcp.OAuthClientCredentials
import app.whittle.core.common.Outcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * The phone's connections as Settings and the library show them, and their changes made: for the phone and the
 * user's computers alike. A service's own sign-in page opens on the phone, from [context].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneConnectionsHost(private val context: Context, private val graph: AppGraph) : ConnectionsHost {
    private val connectors = graph.connectors
    /** Bumped when something the connections don't announce changes: a name, the tools switched off. */
    private val edits = MutableStateFlow(0)

    // Each connection's own state, so a sign-in finishing or a server going quiet shows at once.
    private val withStates: Flow<List<Pair<McpConnection, ConnectorState>>> = connectors.all.flatMapLatest { all ->
        if (all.isEmpty()) flowOf(emptyList()) else combine(all.map { connection -> connection.state.map { connection to it } }) { it.toList() }
    }

    override val state: StateFlow<ConnectionsState> = combine(withStates, edits) { connections, _ -> stateOf(connections) }
        .stateIn(graph.appScope, SharingStarted.Eagerly, ConnectionsState())

    override val failures: Flow<String> = connectors.failures

    override fun act(action: ConnectionAction) {
        when (action) {
            is ConnectionAction.Connect -> find(action.id)?.let { connectors.connect(context, it) }
            is ConnectionAction.ConnectWithKeys -> find(action.id)?.let { connectors.connectWithHeaders(it, action.values) }
            is ConnectionAction.Rename -> find(action.id)?.let { connectors.rename(it, action.name) }
            is ConnectionAction.Remove -> find(action.id)?.let(connectors::remove)
            is ConnectionAction.Disconnect -> find(action.id)?.let(connectors::disconnect)
            is ConnectionAction.SetTool -> find(action.id)?.let { connection ->
                val off = connection.excludedTools
                connectors.setExcludedTools(connection, if (action.on) off - action.tool else off + action.tool)
            }
        }
        edits.update { it + 1 }
    }

    override suspend fun addFromLibrary(url: String): ConnectionAdd {
        val server = connectors.library.firstOrNull { it.url == url } ?: return ConnectionAdd.Failed()
        return addedOf(connectors.add(server))
    }

    override suspend fun add(connection: NewConnection, keys: Map<String, String>): ConnectionAdd {
        val added = connectors.add(
            name = connection.name,
            url = connection.url,
            transport = connection.transport?.let(TRANSPORTS::getValue),
            headers = connection.keyNames.map { HeaderSpec(it, isRequired = true, isSecret = true) },
            auth = connection.auth?.let(AUTH_MODES::getValue),
            scopes = connection.scopes,
            client = connection.clientId?.let { OAuthClientCredentials(it, connection.clientSecret) },
        )
        if (added is Outcome.Success && keys.isNotEmpty()) connectors.connectWithHeaders(added.value, keys)
        return addedOf(added)
    }

    override suspend fun returnAddressFor(name: String): String = connectors.returnAddressFor(name)

    private fun addedOf(outcome: Outcome<McpConnection>): ConnectionAdd = when (outcome) {
        is Outcome.Success -> ConnectionAdd.Added(outcome.value.server.id).also { edits.update { it + 1 } }
        is Outcome.Failure -> ConnectionAdd.Failed(outcome.error.message)
    }

    private fun find(id: String): McpConnection? = connectors.all.value.firstOrNull { it.server.id == id }

    private fun stateOf(connections: List<Pair<McpConnection, ConnectorState>>) = ConnectionsState(
        connections = connections.map { (connection, state) -> entryOf(connection, state) },
        categories = connectors.categories.map { LibraryCategoryEntry(it.id, it.title) },
        library = connectors.library.map { server ->
            LibraryEntry(
                url = server.url,
                title = server.title,
                description = server.description,
                websiteUrl = server.websiteUrl,
                category = server.category,
                keywords = server.keywords,
                added = connectors.added(server.url) != null,
            )
        },
    )

    private fun entryOf(connection: McpConnection, state: ConnectorState): ConnectionEntry {
        val server = connection.server
        val off = connection.excludedTools
        return ConnectionEntry(
            id = server.id,
            name = server.name,
            icon = server.icon,
            logoHost = connectors.logoHost(connection),
            url = server.url,
            websiteUrl = server.websiteUrl,
            description = server.description,
            category = server.category,
            custom = connectors.isCustom(connection),
            auth = AUTH_MODES.entries.first { it.value == server.auth }.key,
            status = when (state) {
                ConnectorState.Disconnected -> ConnectionStatus.Disconnected
                ConnectorState.Connecting -> ConnectionStatus.Connecting
                is ConnectorState.Connected -> ConnectionStatus.Connected
                ConnectorState.SignInExpired -> ConnectionStatus.SignInExpired
            },
            reachable = (state as? ConnectorState.Connected)?.reachable ?: true,
            tools = connection.offered.map { ConnectionTool(it.name, it.description, on = it.name !in off) },
            keys = server.headers.map { KeyField(it.name, it.description, required = it.isRequired, secret = it.isSecret) },
        )
    }

    private companion object {
        val AUTH_MODES = mapOf(ConnectionAuth.Service to McpAuthMode.OAUTH, ConnectionAuth.Key to McpAuthMode.TOKEN, ConnectionAuth.None to McpAuthMode.NONE)
        val TRANSPORTS = mapOf(ConnectionTransport.StreamableHttp to McpTransportKind.STREAMABLE_HTTP, ConnectionTransport.Sse to McpTransportKind.SSE)
    }
}
