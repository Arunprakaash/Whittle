package app.whittle.connectors

import app.whittle.core.config.PromptTemplate
import app.whittle.connectors.mcp.LoopbackRedirects
import app.whittle.connectors.mcp.OAuthClientCredentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.whittle.connectors.mcp.McpTransportKind
import app.whittle.connectors.mcp.McpRegistry
import app.whittle.connectors.mcp.LibraryCategory
import app.whittle.connectors.mcp.LibraryServer
import app.whittle.connectors.mcp.HeaderSpec
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import app.whittle.connectors.mcp.ConnectorsConfig
import app.whittle.connectors.mcp.McpAuthMode
import app.whittle.connectors.mcp.McpConnection
import app.whittle.connectors.mcp.McpCredentialStore
import app.whittle.connectors.mcp.McpCredentials
import app.whittle.connectors.mcp.McpProbe
import app.whittle.connectors.mcp.McpServerConfig
import app.whittle.core.common.EpochClock
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.getOrNull
import app.whittle.storage.secrets.SecretKey
import app.whittle.storage.secrets.SecretStore
import app.whittle.tools.api.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

/**
 * The remote MCP services the user connects with their own account: the bundled ones (Supabase) and any
 * the user adds. Sign-ins stay in the encrypted secret store; each service's tools join the agent's tools.
 */
class Connectors(
    private val secrets: SecretStore,
    private val config: ConnectorsConfig,
    private val http: OkHttpClient,
    private val clock: EpochClock,
    private val scope: CoroutineScope,
    private val directory: File,
    private val clientVersion: String,
    private val custom: CustomConnectorStore,
    /** The page the browser shows when a sign-in comes back to the phone's loopback address. */
    loopbackPage: PromptTemplate,
) {
    private val bundledIds = config.servers.map { it.id }.toSet()
    /** Catches sign-ins that return to a loopback address (servers that refuse app links); null when none do. */
    private val loopback = config.custom.redirectBase.toHttpUrlOrNull()?.takeIf { !it.isHttps }?.let { base ->
        LoopbackRedirects(base, config.custom.loopback, loopbackPage, scope, clock::nowMillis, ::complete)
    }
    private val _all = MutableStateFlow(config.servers.map(::connection))
    private val changes = Mutex()
    private val probe = McpProbe(config, http)

    /** Whittle's library, grouped by [categories]; the agent can also search the MCP Registry. */
    val library: List<LibraryServer> = config.library.servers
    val categories: List<LibraryCategory> = config.library.categories
    val registry = McpRegistry(config.library, http)

    /** Every service, bundled first, then the user's in the order they added them. */
    val all: StateFlow<List<McpConnection>> = _all.asStateFlow()

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A sign-in or refresh that failed, in words the user can act on. */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    init {
        scope.launch {
            changes.withLock { _all.update { it + custom.load().map { saved -> connection(serverFor(saved)) } } }
            _all.value.forEach { connection ->
                connection.exclude(custom.excludedTools(connection.server.id))
                connection.restore()
            }
        }
    }

    /** Every connected service's tools, as they are right now. */
    fun tools(): List<Tool> = _all.value.flatMap { it.tools }

    fun isCustom(connection: McpConnection): Boolean = connection.server.id !in bundledIds

    /**
     * Adds a server the user (or the agent, with the user's OK) names: finds how it signs in, unless [auth]
     * says, saves it, and connects it at once when it needs no sign-in. [scopes] and [client] are for a
     * browser sign-in: what to ask for, and a client the user registered with the service themselves.
     */
    suspend fun add(
        name: String,
        url: String,
        transport: McpTransportKind = probe.transportFor(url.trim()),
        headers: List<HeaderSpec> = emptyList(),
        websiteUrl: String? = null,
        redirectBase: String? = null,
        auth: McpAuthMode? = null,
        scopes: List<String> = emptyList(),
        client: OAuthClientCredentials? = null,
    ): Outcome<McpConnection> {
        added(url)?.let { return Outcome.Success(it) }
        val signIn = when {
            auth != null -> auth
            // A server that names a required header wants a key, whatever the probe saw.
            headers.any { it.isRequired } -> McpAuthMode.TOKEN
            else -> when (val probed = probe.detect(url.trim(), transport)) {
                is Outcome.Success -> probed.value
                is Outcome.Failure -> return probed
            }
        }
        val connection = changes.withLock {
            val existing = _all.value.map { it.server.id }.toSet()
            // Added by address (the agent, or the user's own entry): a library server's sign-in rules still apply.
            val returnBase = redirectBase ?: library.firstOrNull { it.url.trimEnd('/') == url.trim().trimEnd('/') }?.redirectBase
            val saved = CustomServer(uniqueId(name, existing), name.trim().ifEmpty { url }, url.trim(), signIn, transport, headers, websiteUrl, returnBase, scopes)
            custom.save(custom.load() + saved)
            connection(serverFor(saved)).also { added -> _all.update { it + added } }
        }
        client?.let { connection.useClient(it) }
        if (signIn == McpAuthMode.NONE) (connection.connectOpen() as? Outcome.Failure)?.let { return it }
        return Outcome.Success(connection)
    }

    /**
     * Where a connection named [name] would have its sign-in return, so the user can register it with a
     * service before adding it (see the client in [add]).
     */
    fun returnAddressFor(name: String): String =
        config.custom.redirectBase + uniqueId(name, _all.value.map { it.server.id }.toSet())

    /** A library server, added the same way. */
    suspend fun add(server: LibraryServer): Outcome<McpConnection> =
        add(server.title, server.url, server.transport, server.headers, server.websiteUrl, server.redirectBase)

    /**
     * Where [connection]'s logo comes from: its website, else the library's entry for its address (for servers
     * added before websites were kept), else the server's own host.
     */
    fun logoHost(connection: McpConnection): String? {
        val server = connection.server
        val site = server.websiteUrl ?: library.firstOrNull { it.url.trimEnd('/') == server.url.trimEnd('/') }?.websiteUrl ?: server.url
        return site.toHttpUrlOrNull()?.host
    }

    /** The connection already made to [url], if any. */
    fun added(url: String): McpConnection? = _all.value.firstOrNull { it.server.url.trimEnd('/') == url.trim().trimEnd('/') }

    /** Switches tools of [connection] off (or back on), remembered across restarts. */
    fun setExcludedTools(connection: McpConnection, names: Set<String>) {
        connection.exclude(names)
        scope.launch { custom.setExcludedTools(connection.server.id, names) }
    }

    /**
     * Renames a server the user added. Only what the user sees changes: its tools, sign-in and switched-off
     * tools are keyed by its id, which stays.
     */
    fun rename(connection: McpConnection, name: String) {
        val trimmed = name.trim()
        if (!isCustom(connection) || trimmed.isEmpty() || trimmed == connection.server.name) return
        scope.launch {
            val renamed = changes.withLock {
                val saved = custom.load().map { if (it.id == connection.server.id) it.copy(name = trimmed) else it }
                custom.save(saved)
                val server = saved.firstOrNull { it.id == connection.server.id } ?: return@launch
                connection(serverFor(server)).also { fresh -> _all.update { list -> list.map { if (it === connection) fresh else it } } }
            }
            renamed.exclude(connection.excludedTools)
            renamed.restore()
        }
    }

    /** Forgets a server the user added, with its sign-in. */
    fun remove(connection: McpConnection) {
        if (!isCustom(connection)) return
        scope.launch {
            connection.disconnect()
            secrets.delete(secretKey(connection.server.id))
            changes.withLock {
                custom.save(custom.load().filterNot { it.id == connection.server.id })
                _all.update { list -> list.filterNot { it === connection } }
            }
        }
    }

    /** Signs in: the service's page in a browser tab, or straight away for a server without sign-in. */
    fun connect(context: Context, connection: McpConnection) {
        scope.launch {
            when (connection.server.auth) {
                McpAuthMode.OAUTH -> {
                    // Listen before the browser opens, so the return can't arrive first.
                    val ready = loopback?.takeIf { it.serves(connection.server.redirectUri) }?.listen()
                    if (ready is Outcome.Failure) {
                        _failures.tryEmit(ready.error.message)
                        return@launch
                    }
                    when (val page = connection.beginSignIn()) {
                        is Outcome.Success -> openSignIn(context, page.value)
                        is Outcome.Failure -> _failures.tryEmit(page.error.message)
                    }
                }
                McpAuthMode.NONE -> (connection.connectOpen() as? Outcome.Failure)?.let { _failures.tryEmit(it.error.message) }
                // The token comes from the user in Settings; nothing to open here.
                McpAuthMode.TOKEN -> Unit
            }
        }
    }

    /**
     * Connects with keys the user typed, by header name. A bare key for Authorization becomes a bearer token,
     * as servers expect.
     */
    fun connectWithHeaders(connection: McpConnection, values: Map<String, String>) {
        val headers = values.mapValues { (name, value) ->
            val key = value.trim()
            if (name.equals(AUTHORIZATION, ignoreCase = true) && key.isNotEmpty() && ' ' !in key) "$BEARER $key" else key
        }
        scope.launch {
            (connection.connectWithHeaders(headers) as? Outcome.Failure)?.let { _failures.tryEmit(it.error.message) }
        }
    }

    fun disconnect(connection: McpConnection) {
        scope.launch { connection.disconnect() }
    }

    /** The browser sent the user back; returns false if no service was waiting for this address. */
    fun complete(callbackUrl: String): Boolean {
        val connection = _all.value.firstOrNull { it.handles(callbackUrl) } ?: return false
        scope.launch {
            (connection.complete(callbackUrl) as? Outcome.Failure)?.let { _failures.tryEmit(it.error.message) }
        }
        return true
    }

    private fun openSignIn(context: Context, url: String) {
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        // The agent can start a sign-in from a task, with no screen of its own to open it from.
        if (context !is Activity) tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(context, Uri.parse(url))
    }

    private fun serverFor(saved: CustomServer): McpServerConfig = with(config.custom) {
        McpServerConfig(
            id = saved.id,
            name = saved.name,
            icon = icon,
            url = saved.url,
            redirectUri = (saved.redirectBase ?: redirectBase) + saved.id,
            scopes = saved.scopes,
            toolPrefix = "$toolPrefix${saved.id}_",
            sourceLabel = saved.name,
            defaultRiskTier = defaultRiskTier,
            auth = saved.auth,
            transport = saved.transport,
            headers = saved.headers,
            websiteUrl = saved.websiteUrl,
        )
    }

    /** A short id from the name ("Linear" → linear), numbered if taken. */
    private fun uniqueId(name: String, taken: Set<String>): String {
        val base = name.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(config.custom.maxNameChars).ifEmpty { FALLBACK_ID }
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { it !in taken }
    }

    private fun connection(server: McpServerConfig) = McpConnection(
        server = server,
        config = config,
        http = http,
        clock = clock,
        credentials = SecretCredentials(secrets, secretKey(server.id)),
        toolCache = File(directory, server.id + TOOL_CACHE_SUFFIX),
        clientVersion = clientVersion,
    )

    private fun secretKey(id: String) = SecretKey(SECRET_PREFIX + id)

    private class SecretCredentials(private val secrets: SecretStore, private val key: SecretKey) : McpCredentialStore {
        override suspend fun load(): McpCredentials? = secrets.get(key).getOrNull()?.let { stored ->
            try {
                json.decodeFromString(McpCredentials.serializer(), stored.reveal())
            } catch (exception: SerializationException) {
                null // Written by an older version; the user connects again.
            }
        }

        override suspend fun save(credentials: McpCredentials) {
            val written = secrets.put(key, Secret(json.encodeToString(McpCredentials.serializer(), credentials)))
            if (written is Outcome.Failure) throw IllegalStateException(written.error.message)
        }

        override suspend fun clear() {
            secrets.delete(key)
        }
    }

    private companion object {
        const val SECRET_PREFIX = "connector:"
        const val TOOL_CACHE_SUFFIX = "-tools.json"
        const val FALLBACK_ID = "server"
        const val AUTHORIZATION = "Authorization"
        const val BEARER = "Bearer"
        val json = Json { ignoreUnknownKeys = true }
    }
}

