package app.whittle.dev.cli

import app.whittle.agent.AgentMessages
import app.whittle.agent.AgentRunner
import app.whittle.agent.AskUserTool
import app.whittle.agent.ForgetTool
import app.whittle.agent.InMemoryRunCheckpointStore
import app.whittle.agent.MemoryStore
import app.whittle.agent.PauseReason
import app.whittle.agent.RememberTool
import app.whittle.agent.RunEvent
import app.whittle.agent.RunInbox
import app.whittle.agent.RunRequest
import app.whittle.agent.SystemPromptVariables
import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.getOrNull
import app.whittle.core.config.HarnessConfig
import app.whittle.core.config.PromptTemplate
import app.whittle.core.model.Message
import app.whittle.core.model.RunId
import app.whittle.llm.api.CredentialsProvider
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.api.ReasoningEffort
import app.whittle.observability.LocalTelemetry
import app.whittle.policy.PolicyRuleSet
import app.whittle.policy.RuleBasedPolicyEngine
import app.whittle.policy.RunMode
import app.whittle.tools.api.ToolRegistry
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.device.DeviceClockTool
import app.whittle.tools.web.WebFetchTool
import app.whittle.tools.workspace.ProcessManager
import app.whittle.tools.workspace.ShellEnvironment
import app.whittle.tools.workspace.TerminalConfig
import app.whittle.tools.workspace.WorkspaceOutputs
import app.whittle.tools.workspace.WorkspaceToolSet
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Everything a run needs that doesn't change between runs: config, the key, the model adapters. */
class CliSetup(
    val harness: HarnessConfig,
    val providers: ProviderCatalog,
    val credentials: CredentialsProvider,
    val rules: PolicyRuleSet,
    val messages: AgentMessages,
    val systemPrompt: PromptTemplate,
    val terminal: TerminalConfig,
    val httpClient: OkHttpClient,
    val adapters: ModelAdapterRegistry,
)

/**
 * One agent session on the desktop: a fresh workspace in a temp folder using the desktop's own shell, the memory in
 * [memoryFile], and approvals answered by [approvalMode]. The same AgentRunner, policy engine and tools as the app.
 */
class CliSession(private val setup: CliSetup, approvalMode: ApprovalMode, memoryFile: Path, scope: CoroutineScope) {
    private val clock = EpochClock.System
    private val inbox = RunInbox()
    private val decisions = ConsoleDecisions(approvalMode, inbox)
    private val workspaces = Workspaces(Files.createTempDirectory(WORKSPACE_PREFIX).toFile())

    /** The workspace's folder on the desktop. */
    val workspace: File = requireNotNull(workspaces.directory(WorkspaceId(CLI_WORKSPACE)).getOrNull()) { "No workspace folder" }

    private val memory = MemoryStore(JsonFileMemory(memoryFile), setup.harness.agent.memory, setup.messages, clock, IdGenerator.Random, scope)

    private val runner = AgentRunner(
        adapters = setup.adapters,
        providers = setup.providers,
        credentials = setup.credentials,
        tools = ToolRegistry(
            setOf(AskUserTool(), DeviceClockTool(clock), WebFetchTool(setup.httpClient), RememberTool(memory, setup.messages), ForgetTool(memory, setup.messages)) +
                workspaceTools(),
        ),
        policy = RuleBasedPolicyEngine(setup.rules),
        inbox = inbox,
        checkpoints = InMemoryRunCheckpointStore(),
        telemetry = LocalTelemetry(IdGenerator.Random),
        limits = setup.harness.agent,
        systemPrompt = setup.systemPrompt,
        messages = setup.messages,
        clock = clock,
        outputs = WorkspaceOutputs(workspaces, setup.harness.agent.toolOutput.directory)::save,
        memory = memory,
    )

    /**
     * Runs [prompt] to the end, passing every event to [onEvent]. Answers given after the last step continue the run, as in
     * the app; [onContinue] is told when that happens.
     */
    suspend fun run(model: ModelDescriptor, reasoningEffort: ReasoningEffort, prompt: String, onEvent: (RunEvent) -> Unit, onContinue: () -> Unit = {}) {
        val runId = RunId(IdGenerator.Random.newId())
        var events = runner.start(
            RunRequest(
                runId = runId,
                workspace = WorkspaceId(CLI_WORKSPACE),
                model = model,
                userMessage = Message.userText(prompt),
                mode = RunMode.LIVE,
                reasoningEffort = reasoningEffort,
                preApprovedTools = emptySet(),
                promptVariables = mapOf(
                    SystemPromptVariables.CURRENT_DATE to LocalDate.now().toString(),
                    SystemPromptVariables.USER_LOCALE to Locale.getDefault().toLanguageTag(),
                    SystemPromptVariables.USER_TIME_ZONE to ZoneId.systemDefault().id,
                    // The CLI has no chat projects: every command starts at the workspace.
                    SystemPromptVariables.PROJECT to "",
                ),
            ),
        )
        while (true) {
            var openDecisions = false
            events.collect { event ->
                onEvent(event)
                if (event is RunEvent.DecisionRequested) decisions.answer(runId, event)
                if (event is RunEvent.Completed) openDecisions = event.openDecisions.isNotEmpty()
                if (event is RunEvent.Paused && event.reason == PauseReason.AWAITING_USER) openDecisions = true
            }
            if (!openDecisions || !inbox.hasInput(runId)) break
            onContinue()
            events = runner.resume(runId)
        }
    }

    private fun workspaceTools(): List<app.whittle.tools.api.Tool> {
        val environment = ShellEnvironment(shell = DESKTOP_SHELL, path = System.getenv("PATH").orEmpty(), launcher = null, killProcessGroup = null)
        return WorkspaceToolSet.create(
            workspaces = workspaces,
            environment = environment,
            config = setup.terminal,
            // Processes end with the CLI.
            processes = ProcessManager(environment, setup.terminal.processes, CoroutineScope(SupervisorJob() + Dispatchers.IO)),
        )
    }

    private companion object {
        const val CLI_WORKSPACE = "cli"
        const val WORKSPACE_PREFIX = "whittle-cli"
        const val DESKTOP_SHELL = "/bin/sh"
    }
}
