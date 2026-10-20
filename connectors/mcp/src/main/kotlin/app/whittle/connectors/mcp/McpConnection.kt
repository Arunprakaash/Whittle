package app.whittle.connectors.mcp

import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.tools.api.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** Where a connector's registration and tokens are kept; the app backs it with its encrypted secret store. */
interface McpCredentialStore {
    suspend fun load(): McpCredentials?

    suspend fun save(credentials: McpCredentials)

    suspend fun clear()
}

sealed interface ConnectorState {
    data object Disconnected : ConnectorState

    data object Connecting : ConnectorState

    data class Connected(val toolCount: Int) : ConnectorState
}

/**
 * One remote MCP server the user connects with their own account. Its tools join the agent's tools once
 * the user signs in, and stay available offline from a cached list until the next refresh.
 */
class McpConnection(
    val server: McpServerConfig,
    private val config: ConnectorsConfig,
    http: OkHttpClient,
    private val clock: EpochClock,
    private val credentials: McpCredentialStore,
    /** The server's tool list, kept so tools are there at start-up without a network call. */
    private val toolCache: File,
    clientVersion: String,
) {
    private val authorizer = McpAuthorizer(server, config, http, clock)
    private val client = McpClient(server, config, http, ::accessToken, clientVersion)
    private val tokenLock = Mutex()
    private val _state = MutableStateFlow<ConnectorState>(ConnectorState.Disconnected)
    val state: StateFlow<ConnectorState> = _state.asStateFlow()

    @Volatile private var pending: PendingSignIn? = null

    @Volatile private var signedIn = false

    @Volatile var tools: List<Tool> = emptyList()
        private set

    /** At start-up: the cached tools if the user is signed in, then a fresh list from the server. */
    suspend fun restore() {
        if (credentials.load()?.tokens == null) return
        signedIn = true
        publish(readCache().orEmpty())
        refreshTools()
    }

    /** Returns the page where the user signs in; [complete] finishes once the browser sends them back. */
    suspend fun beginSignIn(): Outcome<String> {
        _state.value = ConnectorState.Connecting
        return when (val started = authorizer.begin(credentials.load()?.client)) {
            is Outcome.Success -> {
                pending = started.value
                credentials.save(McpCredentials(started.value.client, credentials.load()?.tokens))
                Outcome.Success(started.value.authorizationUrl)
            }
            is Outcome.Failure -> started.also { settle() }
        }
    }

    /** Whether [callbackUrl] is this connector's sign-in coming back. */
    fun handles(callbackUrl: String): Boolean = callbackUrl.substringBefore('?') == server.redirectUri

    suspend fun complete(callbackUrl: String): Outcome<Unit> {
        val signIn = pending ?: return Outcome.Failure(HarnessError.Authentication("That sign-in has expired; connect ${server.name} again")).also { settle() }
        pending = null
        val tokens = when (val code = authorizer.codeFrom(callbackUrl, signIn)) {
            is Outcome.Success -> authorizer.exchange(code.value, signIn)
            is Outcome.Failure -> code
        }
        return when (tokens) {
            is Outcome.Success -> {
                credentials.save(McpCredentials(signIn.client, tokens.value))
                signedIn = true
                client.reset()
                refreshTools()
            }
            is Outcome.Failure -> tokens.also { settle() }
        }
    }

    /** The user cancelled in the browser or left it; nothing changes. */
    fun cancelSignIn() {
        pending = null
        settle()
    }

    /** Forgets the tokens and tools on this phone; the registration stays so connecting again is quick. */
    suspend fun disconnect() {
        credentials.load()?.let { credentials.save(it.copy(tokens = null)) }
        signedIn = false
        client.reset()
        withContext(Dispatchers.IO) { toolCache.delete() }
        publish(emptyList())
    }

    suspend fun refreshTools(): Outcome<Unit> = when (val listed = client.listTools()) {
        is Outcome.Success -> {
            writeCache(listed.value)
            publish(listed.value)
            Outcome.Success(Unit)
        }
        is Outcome.Failure -> listed.also { settle() }
    }

    internal suspend fun call(tool: String, arguments: JsonObject): Outcome<RemoteResult> = client.callTool(tool, arguments)

    private suspend fun accessToken(refresh: Boolean): Outcome<String> = tokenLock.withLock {
        val stored = credentials.load() ?: return@withLock signedOut()
        val tokens = stored.tokens ?: return@withLock signedOut()
        val expiring = tokens.expiresAtMillis?.let { it - config.refreshBeforeExpiryMillis <= clock.nowMillis() } == true
        if (!refresh && !expiring) return@withLock Outcome.Success(tokens.accessToken)
        when (val renewed = authorizer.refresh(stored.client, tokens)) {
            is Outcome.Success -> {
                credentials.save(stored.copy(tokens = renewed.value))
                Outcome.Success(renewed.value.accessToken)
            }
            is Outcome.Failure -> {
                // A refused refresh token means the user must sign in again; a network failure doesn't.
                if (renewed.error is HarnessError.Authentication) disconnect()
                renewed
            }
        }
    }

    private fun publish(remote: List<RemoteTool>) {
        tools = remote.map { McpTool(it, server, this) }
        settle()
    }

    private fun settle() {
        _state.value = when {
            signedIn -> ConnectorState.Connected(tools.size)
            pending != null -> ConnectorState.Connecting
            else -> ConnectorState.Disconnected
        }
    }

    private suspend fun readCache(): List<RemoteTool>? = withContext(Dispatchers.IO) {
        try {
            toolCache.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(TOOL_LIST, it) }
        } catch (exception: IOException) {
            null
        } catch (exception: SerializationException) {
            null // Written by an older version; the refresh replaces it.
        }
    }

    private suspend fun writeCache(remote: List<RemoteTool>) = withContext(Dispatchers.IO) {
        try {
            toolCache.parentFile?.mkdirs()
            toolCache.writeText(json.encodeToString(TOOL_LIST, remote))
        } catch (exception: IOException) {
            // Only start-up speed is lost; the next refresh tries again.
        }
    }

    private fun signedOut(): Outcome.Failure = Outcome.Failure(HarnessError.Authentication("${server.name} isn't connected; connect it in Settings"))

    private companion object {
        val TOOL_LIST = ListSerializer(RemoteTool.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
