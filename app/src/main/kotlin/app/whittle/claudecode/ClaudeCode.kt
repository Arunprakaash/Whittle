package app.whittle.claudecode

import app.whittle.agent.RunInbox
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrNull
import app.whittle.core.model.ModelId
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.ModelCapabilities
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.TrustTier
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.CommandResult
import app.whittle.tools.workspace.CommandRunner
import app.whittle.tools.workspace.InteractiveCommand
import app.whittle.tools.workspace.ShellEnvironment
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Where Claude Code stands on this phone. */
sealed interface ClaudeCodeState {
    data object Checking : ClaudeCodeState

    /** Linux isn't set up, so Claude Code has nowhere to run. */
    data object NoLinux : ClaudeCodeState

    data object NotInstalled : ClaudeCodeState

    data object Installing : ClaudeCodeState

    /** [account] is who's signed in, when Claude Code says. */
    data class Ready(val signedIn: Boolean, val account: ClaudeCodeAccount? = null) : ClaudeCodeState

    /** Installing or removing failed; [detail] is the command's last line. */
    data class Failed(val detail: String) : ClaudeCodeState
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

    private const val EMAIL = "email"
    private const val SUBSCRIPTION = "subscriptionType"
}

/**
 * Claude Code in Whittle's Linux: installed unchanged from Anthropic, signed in on its own screen, and run for the
 * chat's tasks. Whittle runs its commands and never reads, stores or forwards its sign-in.
 */
class ClaudeCode(
    val config: ClaudeCodeConfig,
    private val environment: ShellEnvironment,
    private val workspaces: Workspaces,
    private val scope: CoroutineScope,
    sessionsFile: File,
    inbox: RunInbox,
) {
    private val commands = CommandRunner(environment, config.outputChars)
    private val mutableState = MutableStateFlow<ClaudeCodeState>(ClaudeCodeState.Checking)
    val state: StateFlow<ClaudeCodeState> = mutableState.asStateFlow()

    val sessions = ClaudeCodeSessions(sessionsFile)

    private val mutableLimit = MutableStateFlow<ClaudeCodeLimit?>(null)

    /** The last Claude plan limit a task ran into, for the chat to say which one and when it reopens. */
    val limit: StateFlow<ClaudeCodeLimit?> = mutableLimit.asStateFlow()

    val runner = ClaudeCodeRunner(config, environment, sessions, inbox, onLimit = { mutableLimit.value = it }) { chat -> folder(chat) }

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

    /** Runs Claude Code's own sign-out. */
    fun signOut(): Job = scope.launch {
        run(config.signOutCommand, config.commandTimeoutSeconds, network = true)
        mutableState.value = check()
    }

    /** Removes Claude Code, and with it the chats' Claude Code sessions. */
    fun remove(): Job = scope.launch {
        mutableState.value = ClaudeCodeState.Checking
        val ran = run(config.removeCommand, config.installTimeoutSeconds, network = false)
        sessions.clear()
        mutableState.value = if (ran != null && ran.exitCode != 0) ClaudeCodeState.Failed(lastLine(ran)) else check()
    }

    /** Starts Claude Code's own sign-in; null when commands can't run yet. */
    suspend fun signIn(): InteractiveCommand? {
        val folder = folder(null) ?: return null
        return InteractiveCommand.start(environment, config.signInCommand, folder, network = true).getOrNull()
    }

    private suspend fun check(): ClaudeCodeState {
        val version = run(config.versionCommand, config.commandTimeoutSeconds, network = false) ?: return ClaudeCodeState.NoLinux
        if (version.exitCode != 0) return ClaudeCodeState.NotInstalled
        val status = run(config.statusCommand, config.commandTimeoutSeconds, network = false) ?: return ClaudeCodeState.NoLinux
        val signedIn = status.exitCode == 0
        return ClaudeCodeState.Ready(signedIn, if (signedIn) ClaudeCodeStatus.account(status.stdout, config.planNames) else null)
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
}
