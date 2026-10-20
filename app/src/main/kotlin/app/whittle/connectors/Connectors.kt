package app.whittle.connectors

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import app.whittle.connectors.mcp.ConnectorsConfig
import app.whittle.connectors.mcp.McpConnection
import app.whittle.connectors.mcp.McpCredentialStore
import app.whittle.connectors.mcp.McpCredentials
import app.whittle.core.common.EpochClock
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.getOrNull
import app.whittle.storage.secrets.SecretKey
import app.whittle.storage.secrets.SecretStore
import app.whittle.tools.api.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

/**
 * The remote services the user can connect with their own account (Supabase). Sign-in happens in a browser
 * tab; the tokens stay in the encrypted secret store and the services' tools join the agent's tools.
 */
class Connectors(
    context: Context,
    secrets: SecretStore,
    config: ConnectorsConfig,
    http: OkHttpClient,
    clock: EpochClock,
    private val scope: CoroutineScope,
    directory: File,
    clientVersion: String,
) {
    val all: List<McpConnection> = config.servers.map { server ->
        McpConnection(
            server = server,
            config = config,
            http = http,
            clock = clock,
            credentials = SecretCredentials(secrets, SecretKey(SECRET_PREFIX + server.id)),
            toolCache = File(directory, server.id + TOOL_CACHE_SUFFIX),
            clientVersion = clientVersion,
        )
    }

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A sign-in or refresh that failed, in words the user can act on. */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    init {
        scope.launch { all.forEach { it.restore() } }
    }

    /** Every connected service's tools, as they are right now. */
    fun tools(): List<Tool> = all.flatMap { it.tools }

    /** Opens the service's sign-in page in a browser tab inside Whittle. */
    fun connect(context: Context, connection: McpConnection) {
        scope.launch {
            when (val page = connection.beginSignIn()) {
                is Outcome.Success -> CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(page.value))
                is Outcome.Failure -> _failures.tryEmit(page.error.message)
            }
        }
    }

    fun disconnect(connection: McpConnection) {
        scope.launch { connection.disconnect() }
    }

    /** The browser sent the user back; returns false if no connector was waiting for this address. */
    fun complete(callbackUrl: String): Boolean {
        val connection = all.firstOrNull { it.handles(callbackUrl) } ?: return false
        scope.launch {
            (connection.complete(callbackUrl) as? Outcome.Failure)?.let { _failures.tryEmit(it.error.message) }
        }
        return true
    }

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
            check(written !is Outcome.Failure) { (written as Outcome.Failure).error.message }
        }

        override suspend fun clear() {
            secrets.delete(key)
        }
    }

    private companion object {
        const val SECRET_PREFIX = "connector:"
        const val TOOL_CACHE_SUFFIX = "-tools.json"
        val json = Json { ignoreUnknownKeys = true }
    }
}

