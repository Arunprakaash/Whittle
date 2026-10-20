package app.whittle.connectors

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
) {
    private val bundledIds = config.servers.map { it.id }.toSet()
    private val _all = MutableStateFlow(config.servers.map(::connection))
    private val changes = Mutex()
    private val probe = McpProbe(config, http)

    /** Every service, bundled first, then the user's in the order they added them. */
    val all: StateFlow<List<McpConnection>> = _all.asStateFlow()

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A sign-in or refresh that failed, in words the user can act on. */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    init {
        scope.launch {
            changes.withLock { _all.update { it + custom.load().map { saved -> connection(serverFor(saved)) } } }
            _all.value.forEach { it.restore() }
        }
    }

    /** Every connected service's tools, as they are right now. */
    fun tools(): List<Tool> = _all.value.flatMap { it.tools }

    fun isCustom(connection: McpConnection): Boolean = connection.server.id !in bundledIds

    /**
     * Adds a server the user (or the agent, with the user's OK) names: finds how it signs in, saves it, and
     * connects it at once when it needs no sign-in.
     */
    suspend fun add(name: String, url: String): Outcome<McpConnection> {
        val auth = when (val detected = probe.detect(url.trim())) {
            is Outcome.Success -> detected.value
            is Outcome.Failure -> return detected
        }
        val connection = changes.withLock {
            val existing = _all.value.map { it.server.id }.toSet()
            val saved = CustomServer(uniqueId(name, existing), name.trim().ifEmpty { url }, url.trim(), auth)
            custom.save(custom.load() + saved)
            connection(serverFor(saved)).also { added -> _all.update { it + added } }
        }
        if (auth == McpAuthMode.NONE) (connection.connectOpen() as? Outcome.Failure)?.let { return it }
        return Outcome.Success(connection)
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
                McpAuthMode.OAUTH -> when (val page = connection.beginSignIn()) {
                    is Outcome.Success -> openSignIn(context, page.value)
                    is Outcome.Failure -> _failures.tryEmit(page.error.message)
                }
                McpAuthMode.NONE -> (connection.connectOpen() as? Outcome.Failure)?.let { _failures.tryEmit(it.error.message) }
                // The token comes from the user in Settings; nothing to open here.
                McpAuthMode.TOKEN -> Unit
            }
        }
    }

    fun connectWithToken(connection: McpConnection, token: String) {
        scope.launch {
            (connection.connectWithToken(token) as? Outcome.Failure)?.let { _failures.tryEmit(it.error.message) }
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
            redirectUri = redirectBase + saved.id,
            scopes = emptyList(),
            toolPrefix = "$toolPrefix${saved.id}_",
            sourceLabel = saved.name,
            defaultRiskTier = defaultRiskTier,
            auth = saved.auth,
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
        val json = Json { ignoreUnknownKeys = true }
    }
}

