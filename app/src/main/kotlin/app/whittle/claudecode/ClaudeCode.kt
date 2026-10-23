package app.whittle.claudecode

import app.whittle.agent.RunInbox
import app.whittle.connectors.mcp.McpAccess
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.getOrNull
import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.TrustTier
import app.whittle.storage.secrets.SecretKey
import app.whittle.storage.secrets.SecretStore
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.CommandResult
import app.whittle.tools.workspace.CommandRunner
import app.whittle.tools.workspace.FileChanges
import app.whittle.tools.workspace.InteractiveCommand
import app.whittle.tools.workspace.ShellEnvironment
import app.whittle.tools.workspace.Workspaces
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** Where Claude Code stands on this phone. */
sealed interface ClaudeCodeState {
    data object Checking : ClaudeCodeState

    /** Linux isn't set up, so Claude Code has nowhere to run. */
    data object NoLinux : ClaudeCodeState

    data object NotInstalled : ClaudeCodeState

    data object Installing : ClaudeCodeState

    /**
     * [account] is who's signed in, when Claude Code says; [apiKey] when tasks use the user's own API key instead;
     * [version] is the one installed, when it says.
     */
    data class Ready(
        val signedIn: Boolean,
        val account: ClaudeCodeAccount? = null,
        val version: String? = null,
        val apiKey: Boolean = false,
    ) : ClaudeCodeState

    /** Installing or removing failed; [detail] is the command's last line. */
    data class Failed(val detail: String) : ClaudeCodeState
}

/** How checking an Anthropic API key went. */
enum class ApiKeyCheck {
    /** Anthropic took it; it's saved and tasks use it. */
    SAVED,

    /** Anthropic refused it. */
    REFUSED,

    /** Anthropic couldn't be reached; nothing changed. */
    UNREACHABLE,
}

/** How the last update of Claude Code went. */
sealed interface ClaudeCodeUpdate {
    data object Idle : ClaudeCodeUpdate

    data object Running : ClaudeCodeUpdate

    /** [updated] when a newer version came; otherwise it was already the latest. */
    data class Done(val updated: Boolean) : ClaudeCodeUpdate

    /** [detail] is the command's last line. */
    data class Failed(val detail: String) : ClaudeCodeUpdate
}

/** The Claude account Claude Code is signed in with; either part may be unknown. */
data class ClaudeCodeAccount(val email: String?, val plan: String?)

/** A Claude plan limit Claude Code reported: which window ([window], Claude Code's name), and when it reopens. */
data class ClaudeCodeLimit(val window: String?, val resetsAtMillis: Long?)

/** Reads `claude auth status`; pure, so it's tested on the JVM. */
internal object ClaudeCodeStatus {
    private val json = Json { ignoreUnknownKeys = true }

    /** The account in the status JSON, with the plan by [planNames]; null when it names neither. */
    fun account(output: String, planNames: Map<String, String>): ClaudeCodeAccount? {
        val status = try {
            json.parseToJsonElement(output.trim()) as? JsonObject
        } catch (exception: SerializationException) {
            null
        } ?: return null
        val email = (status[EMAIL] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        val plan = (status[SUBSCRIPTION] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            ?.let { raw -> planNames[raw.lowercase()] ?: raw.replaceFirstChar(Char::uppercaseChar) }
        return if (email == null && plan == null) null else ClaudeCodeAccount(email, plan)
    }

    /** The version in `claude --version`, e.g. 2.1.3 from "2.1.3 (Claude Code)"; null when there's none. */
    fun version(output: String): String? = VERSION.find(output)?.value

    private val VERSION = Regex("""\d+(\.\d+)+""")
    private const val EMAIL = "email"
    private const val SUBSCRIPTION = "subscriptionType"
}

/**
 * Claude Code in Whittle's Linux: installed unchanged from Anthropic, signed in on its own screen, and run for the
 * chat's tasks. Whittle runs its commands and never reads, stores or forwards its sign-in to a Claude account. A user
 * who signs in with their own Anthropic API key instead has it kept encrypted, like their other keys.
 */
class ClaudeCode(
    val config: ClaudeCodeConfig,
    private val environment: ShellEnvironment,
    private val workspaces: Workspaces,
    private val scope: CoroutineScope,
    sessionsFile: File,
    /** Touched after each update, so the quiet one runs at most every [ClaudeCodeConfig.updateEveryHours]. */
    private val updatedStamp: File,
    inbox: RunInbox,
    changes: FileChanges,
    maxFileChars: Int,
    /** The user's connections, handed to each task. */
    connections: suspend () -> List<McpAccess>,
    /** The host file at a Linux path; null before Linux is set up. */
    linuxFile: (String) -> File?,
    private val secrets: SecretStore,
    private val http: OkHttpClient,
) {
    private val apiKeySecret = SecretKey(config.apiKey.secretName)

    private val commands = CommandRunner(environment, config.outputChars)
    private val mutableState = MutableStateFlow<ClaudeCodeState>(ClaudeCodeState.Checking)
    val state: StateFlow<ClaudeCodeState> = mutableState.asStateFlow()

    val sessions = ClaudeCodeSessions(sessionsFile)

    private val mutableLimit = MutableStateFlow<ClaudeCodeLimit?>(null)

    /** The last Claude plan limit a task ran into, for the chat to say which one and when it reopens. */
    val limit: StateFlow<ClaudeCodeLimit?> = mutableLimit.asStateFlow()

    val runner = ClaudeCodeRunner(
        config = config,
        environment = environment,
        sessions = sessions,
        inbox = inbox,
        onLimit = { mutableLimit.value = it },
        directory = ::folder,
        locate = { chat, path -> workspaces.locate(chat, path).getOrNull() },
        changes = changes,
        maxFileChars = maxFileChars,
        connections = ClaudeCodeConnections(config, connections, ClaudeCodeTaskFiles(config, linuxFile)),
        files = ClaudeCodeTaskFiles(config, linuxFile),
        apiKey = ::apiKey,
    )

    private val updating = Mutex()
    private val mutableUpdate = MutableStateFlow<ClaudeCodeUpdate>(ClaudeCodeUpdate.Idle)

    /** The update the user asked for, for its row to say how it went. */
    val update: StateFlow<ClaudeCodeUpdate> = mutableUpdate.asStateFlow()

    /** Whether [model] is one Claude Code runs. */
    fun runs(model: ModelDescriptor?): Boolean = config.enabled && model?.providerId?.value == config.providerId

    /** The models to pick, by the names Claude Code takes. */
    val models: List<ModelDescriptor> = config.models.map { model ->
        ModelDescriptor(
            id = ModelId(model.id),
            providerId = ProviderId(config.providerId),
            displayName = "${config.displayName} · ${model.name}",
            contextWindowTokens = null,
            // It reads attached photos with its own tools, by the paths in the message.
            capabilities = ModelCapabilities(toolCalling = true, parallelToolCalls = true, vision = true, promptCaching = true, reasoning = false),
            trustTier = TrustTier.A,
        )
    }

    fun detailOf(model: ModelDescriptor): String = config.models.firstOrNull { it.id == model.id.value }?.detail.orEmpty()

    /** Looks again at whether Claude Code is installed and signed in. */
    fun refresh(): Job = scope.launch { mutableState.value = check() }

    /** Installs Claude Code from Anthropic's repository; its progress shows in [state]. */
    fun install(): Job = scope.launch {
        mutableState.value = ClaudeCodeState.Installing
        mutableState.value = when (val ran = run(config.installCommand, config.installTimeoutSeconds, network = true)) {
            null -> ClaudeCodeState.NoLinux
            else -> if (ran.exitCode == 0) check() else ClaudeCodeState.Failed(lastLine(ran))
        }
    }

    /** Updates Claude Code from Anthropic's repository; how it went shows in [update]. */
    fun update(): Job = scope.launch {
        mutableUpdate.value = ClaudeCodeUpdate.Running
        mutableUpdate.value = updateNow() ?: ClaudeCodeUpdate.Idle
    }

    /**
     * At launch: updates Claude Code quietly when the last update is older than [ClaudeCodeConfig.updateEveryHours],
     * as its own installer keeps it current. A failure (no network, say) leaves it for the next launch.
     */
    suspend fun updateIfDue() {
        if (!config.enabled) return
        val age = System.currentTimeMillis() - updatedStamp.lastModified()
        if (age in 0 until TimeUnit.HOURS.toMillis(config.updateEveryHours.toLong())) return
        updateNow()
    }

    /** Null when there was nothing to update: Linux or Claude Code isn't there, or an update is already running. */
    private suspend fun updateNow(): ClaudeCodeUpdate? {
        if (!updating.tryLock()) return null
        try {
            val installed = run(config.versionCommand, config.commandTimeoutSeconds, network = false)
            if (installed == null || installed.exitCode != 0) return null
            val ran = run(config.updateCommand, config.installTimeoutSeconds, network = true) ?: return null
            if (ran.exitCode != 0) return ClaudeCodeUpdate.Failed(lastLine(ran))
            withContext(Dispatchers.IO) { if (!updatedStamp.setLastModified(System.currentTimeMillis())) updatedStamp.writeText("") }
            val now = check()
            mutableState.value = now
            return ClaudeCodeUpdate.Done(updated = (now as? ClaudeCodeState.Ready)?.version != ClaudeCodeStatus.version(installed.stdout))
        } finally {
            updating.unlock()
        }
    }

    /** Forgets the API key, when tasks use one; otherwise runs Claude Code's own sign-out. */
    fun signOut(): Job = scope.launch {
        if (apiKey() != null) secrets.delete(apiKeySecret) else run(config.signOutCommand, config.commandTimeoutSeconds, network = true)
        mutableState.value = check()
    }

    /**
     * Checks [key] with Anthropic (a free call) and, once it's taken, keeps it encrypted for tasks to use instead of
     * a Claude account.
     */
    suspend fun useApiKey(key: String): ApiKeyCheck {
        val trimmed = key.trim()
        val request = Request.Builder().url(config.apiKey.checkUrl).get()
            .header(config.apiKey.keyHeader, trimmed)
            .apply { config.apiKey.checkHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
        val status = withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { it.code }
            } catch (exception: IOException) {
                null
            }
        } ?: return ApiKeyCheck.UNREACHABLE
        return when (status) {
            in HTTP_OK -> {
                secrets.put(apiKeySecret, Secret(trimmed))
                mutableState.value = check()
                ApiKeyCheck.SAVED
            }
            in HTTP_REFUSED -> ApiKeyCheck.REFUSED
            else -> ApiKeyCheck.UNREACHABLE
        }
    }

    /** The user's API key, when tasks use one. */
    private suspend fun apiKey(): Secret? = secrets.get(apiKeySecret).getOrNull()

    /** Removes Claude Code, and with it the chats' Claude Code sessions. */
    fun remove(): Job = scope.launch {
        mutableState.value = ClaudeCodeState.Checking
        val ran = run(config.removeCommand, config.installTimeoutSeconds, network = false)
        sessions.clear()
        secrets.delete(apiKeySecret)
        mutableState.value = if (ran != null && ran.exitCode != 0) ClaudeCodeState.Failed(lastLine(ran)) else check()
    }

    /**
     * Starts Claude Code's own sign-in with [method]'s account; null when commands can't run yet. Signing in this way
     * replaces an API key, which tasks would otherwise go on using.
     */
    suspend fun signIn(method: ClaudeCodeSignInMethod): InteractiveCommand? {
        val folder = folder(null) ?: return null
        secrets.delete(apiKeySecret)
        return InteractiveCommand.start(environment, config.signIn(method), folder, network = true).getOrNull()
    }

    private suspend fun check(): ClaudeCodeState {
        val version = run(config.versionCommand, config.commandTimeoutSeconds, network = false) ?: return ClaudeCodeState.NoLinux
        if (version.exitCode != 0) return ClaudeCodeState.NotInstalled
        if (apiKey() != null) return ClaudeCodeState.Ready(signedIn = true, version = ClaudeCodeStatus.version(version.stdout), apiKey = true)
        val status = run(config.statusCommand, config.commandTimeoutSeconds, network = false) ?: return ClaudeCodeState.NoLinux
        val signedIn = status.exitCode == 0
        return ClaudeCodeState.Ready(
            signedIn = signedIn,
            account = if (signedIn) ClaudeCodeStatus.account(status.stdout, config.planNames) else null,
            version = ClaudeCodeStatus.version(version.stdout),
        )
    }

    /** Null when commands can't run here yet, e.g. before Linux is set up. */
    private suspend fun run(command: String, timeoutSeconds: Int, network: Boolean): CommandResult? {
        val folder = folder(null) ?: return null
        return when (val ran = commands.run(command, folder, timeoutSeconds, network)) {
            is Outcome.Failure -> null
            is Outcome.Success -> ran.value
        }
    }

    private fun folder(chat: WorkspaceId?): File? {
        val root = workspaces.folder().getOrNull() ?: return null
        val project = chat?.let(workspaces::projectOf) ?: return root
        return File(root, project)
    }

    private fun lastLine(result: CommandResult): String =
        (result.stderr.ifBlank { result.stdout }).lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()

    private companion object {
        val HTTP_OK = 200..299
        /** Unauthorized or forbidden: the key isn't one Anthropic takes. */
        val HTTP_REFUSED = 401..403
    }
}
