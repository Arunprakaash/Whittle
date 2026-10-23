package app.whittle.claudecode

import app.whittle.agent.DecisionAnswer
import app.whittle.agent.PendingDecision
import app.whittle.agent.RunEvent
import app.whittle.agent.RunInbox
import app.whittle.agent.RunInput
import app.whittle.agent.RunRequest
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.Provenance
import app.whittle.core.model.Role
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.policy.ApprovalScope
import app.whittle.policy.DecisionReason
import app.whittle.policy.PolicyDecision
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.InteractiveCommand
import app.whittle.tools.workspace.ShellEnvironment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a chat's task with Claude Code instead of Whittle's own loop, and tells the chat what happens in the same
 * events: streamed words, steps, approval cards and the answer. Claude Code works in the chat's folder in Linux and
 * carries the chat's conversation on in its own session. When it asks permission for a step, the chat shows an
 * approval card, and the user's answer goes back to Claude Code.
 */
class ClaudeCodeRunner(
    private val config: ClaudeCodeConfig,
    private val environment: ShellEnvironment,
    private val sessions: ClaudeCodeSessions,
    private val inbox: RunInbox,
    /** The folder a chat's task works in: its project, or the whole workspace. */
    private val directory: (WorkspaceId?) -> File?,
) {
    fun start(request: RunRequest): Flow<RunEvent> = channelFlow {
        val chat = request.workspace
        val folder = directory(chat) ?: return@channelFlow send(RunEvent.Failed(HarnessError.Storage(NO_WORKSPACE)))
        val command = config.run(request.model.id.value, chat?.let(sessions::get))
        val process = when (val started = InteractiveCommand.start(environment, command, folder, network = true)) {
            is Outcome.Failure -> return@channelFlow send(RunEvent.Failed(started.error))
            is Outcome.Success -> started.value
        }
        // Each message sent gets a result; once every one has, nothing more is coming and Claude Code may end.
        var sent = 0
        var results = 0
        var step = 0
        var apiError: String? = null
        var finished = false
        val asking = ConcurrentHashMap<String, Asking>()
        suspend fun say(text: String) {
            if (process.write(ClaudeCodeStream.userMessage(text))) sent++
        }
        try {
            say(text(request.userMessage))
            val answers = launch {
                while (isActive) {
                    inbox.drain(request.runId).forEach { input ->
                        when (input) {
                            is RunInput.Decision -> asking.remove(input.toolCallId.value)?.let { question ->
                                val approved = input.answer == DecisionAnswer.APPROVED
                                process.write(
                                    if (approved) ClaudeCodeStream.allow(question.requestId, question.input)
                                    else ClaudeCodeStream.deny(question.requestId, config.declinedMessage),
                                )
                                send(RunEvent.DecisionResolved(question.decision, input.answer, output = null))
                            }
                            is RunInput.Steer -> say(input.text)
                            // Claude Code asks no questions or device permissions through Whittle.
                            is RunInput.Answer, is RunInput.CapabilityChanged -> Unit
                        }
                    }
                    delay(config.inboxPollMillis)
                }
            }
            process.lines.collect { line ->
                for (event in ClaudeCodeStream.parse(line)) {
                    when (event) {
                        is ClaudeCodeEvent.Started -> {
                            chat?.let { sessions.put(it, event.sessionId) }
                            send(RunEvent.StepStarted(++step))
                        }
                        is ClaudeCodeEvent.TextDelta -> send(RunEvent.AssistantTextDelta(event.text))
                        is ClaudeCodeEvent.ToolUse -> send(RunEvent.ToolCallDecided(call(event.id, event.name, event.input), ALLOWED))
                        is ClaudeCodeEvent.ToolResult -> send(
                            RunEvent.ToolCallFinished(ContentBlock.ToolResult(ToolCallId(event.id), event.content, event.isError, OUTPUT)),
                        )
                        is ClaudeCodeEvent.PermissionRequest -> {
                            val id = event.toolUseId ?: event.requestId
                            val decision = PendingDecision(call(id, event.toolName, event.input), ToolId(event.toolName), ApprovalScope.EVERY_CALL, step)
                            asking[id] = Asking(event.requestId, event.input, decision)
                            send(RunEvent.DecisionRequested(decision, definition(event.toolName), event.input))
                        }
                        is ClaudeCodeEvent.OtherRequest -> process.write(ClaudeCodeStream.unsupported(event.requestId))
                        is ClaudeCodeEvent.ApiError -> apiError = event.kind
                        is ClaudeCodeEvent.Retrying -> send(RunEvent.ModelRetrying(event.attempt, event.delayMillis))
                        is ClaudeCodeEvent.Finished -> {
                            results++
                            event.sessionId?.let { session -> chat?.let { sessions.put(it, session) } }
                            if (results < sent && !event.isError) continue
                            finished = true
                            process.closeInput()
                            send(
                                if (event.isError) {
                                    RunEvent.Failed(failure(apiError, event.text))
                                } else {
                                    RunEvent.Completed(Message(Role.ASSISTANT, listOf(ContentBlock.Text(event.text))), event.usage, emptyList())
                                },
                            )
                        }
                    }
                }
            }
            answers.cancel()
            if (!finished) send(RunEvent.Failed(HarnessError.Provider(process.exitCode(), STOPPED)))
        } finally {
            process.stop()
        }
    }

    /** A Claude Code task can't be picked up after the app closed; the next message carries the conversation on. */
    fun resume(@Suppress("UNUSED_PARAMETER") runId: RunId): Flow<RunEvent> = flowOf(RunEvent.Failed(HarnessError.Cancelled(NOT_RESUMABLE)))

    /** What the user wrote; attached files are named in the text, and Claude Code opens them itself. */
    private fun text(message: Message): String = message.content.filterIsInstance<ContentBlock.Text>().joinToString(PARAGRAPH) { it.text }

    private fun failure(kind: String?, text: String): HarnessError = when (kind) {
        in config.signedOutErrors -> HarnessError.Authentication(text)
        in config.limitErrors -> HarnessError.Provider(LIMIT_STATUS, text)
        else -> HarnessError.Provider(UNKNOWN_STATUS, text)
    }

    private fun call(id: String, name: String, input: JsonObject) = ContentBlock.ToolCall(ToolCallId(id), name, input.toString())

    /** Claude Code's own tools, described only as far as the chat needs to show them. */
    private fun definition(name: String) = ToolDefinition(
        id = ToolId(name),
        description = "",
        inputSchema = JsonObject(emptyMap()),
        riskTier = RiskTier.WORKSPACE,
        executionSite = ExecutionSite.SANDBOX,
        requiredCapabilities = emptySet(),
        consentTextKey = "",
        outputProvenance = OUTPUT,
    )

    /** A permission Claude Code is waiting on, by the call it's for. */
    private class Asking(val requestId: String, val input: JsonObject, val decision: PendingDecision)

    private companion object {
        val ALLOWED = PolicyDecision.Allow(DecisionReason.Rule("claude-code"))
        /** Claude Code's tools read files and the web; what they return informs, never authorizes. */
        val OUTPUT = Provenance.Untrusted("Claude Code")
        const val PARAGRAPH = "\n\n"
        const val LIMIT_STATUS = 429
        const val UNKNOWN_STATUS = 0
        const val NO_WORKSPACE = "The workspace isn't available"
        const val STOPPED = "Claude Code stopped before it finished"
        const val NOT_RESUMABLE = "A Claude Code task can't continue after the app closed"
    }
}
