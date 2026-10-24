package app.whittle.claudecode

import app.whittle.agent.DecisionAnswer
import app.whittle.agent.PendingDecision
import app.whittle.agent.RunEvent
import app.whittle.agent.RunInbox
import app.whittle.agent.RunInput
import app.whittle.agent.RunRequest
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
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
import app.whittle.tools.workspace.FileChanges
import app.whittle.tools.workspace.InteractiveCommand
import app.whittle.tools.workspace.ShellEnvironment
import app.whittle.tools.workspace.previousText
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs a chat's task with Claude Code instead of Whittle's own loop, and tells the chat what happens in the same
 * events: streamed words, steps, approval cards and the answer. Claude Code works in the chat's folder in Linux and
 * carries the chat's conversation on in its own session. When it asks permission for a step, the chat shows an
 * approval card, and the user's answer goes back to Claude Code. Files it writes are recorded like Whittle's own
 * edits, so they show in the chat's Changes.
 */
class ClaudeCodeRunner(
    private val config: ClaudeCodeConfig,
    private val environment: ShellEnvironment,
    private val sessions: ClaudeCodeSessions,
    private val inbox: RunInbox,
    /** Told when a task runs into a Claude plan limit. */
    private val onLimit: (ClaudeCodeLimit) -> Unit,
    /** The folder a chat's task works in: its project, or the whole workspace. */
    private val directory: (WorkspaceId?) -> File?,
    /** The file a path in a chat's Linux names, when it's one the chat may change. */
    private val locate: (WorkspaceId, String) -> File?,
    private val changes: FileChanges,
    /** Text kept of a file's earlier content; a longer file shows as changed without its lines. */
    private val maxFileChars: Int,
    /** The user's connections, handed to each task. */
    private val connections: ClaudeCodeConnections,
    private val files: ClaudeCodeTaskFiles,
    /** The user's own Anthropic API key, when they signed in with one. */
    private val apiKey: suspend () -> Secret?,
) {
    fun start(request: RunRequest): Flow<RunEvent> = channelFlow {
        val chat = request.workspace
        val folder = directory(chat) ?: return@channelFlow send(RunEvent.Failed(HarnessError.Storage(NO_WORKSPACE)))
        val handed = connections.hand(request.runId.value)
        // The command removes the key's file as soon as it has read it; this is for a task that never started.
        val key = apiKey()?.let { files.write(KEY_PREFIX + request.runId.value, it.reveal()) }
        try {
            run(request, folder, handed, key)
        } finally {
            handed?.file?.remove()
            key?.remove()
        }
    }

    private suspend fun ProducerScope<RunEvent>.run(
        request: RunRequest,
        folder: File,
        handed: ClaudeCodeConnections.Handed?,
        key: ClaudeCodeTaskFiles.TaskFile?,
    ) {
        val chat = request.workspace
        val command = config.run(
            model = request.model.id.value,
            session = chat?.let(sessions::get),
            preApproved = request.preApprovedTools.mapTo(mutableSetOf()) { it.value },
            mcpConfig = handed?.file?.path,
            blocked = handed?.blocked.orEmpty(),
            apiKeyFile = key?.path,
        )
        val process = when (val started = InteractiveCommand.start(environment, command, folder, network = true)) {
            is Outcome.Failure -> return send(RunEvent.Failed(started.error))
            is Outcome.Success -> started.value
        }
        // Each message sent gets a result; once every one has, nothing more is coming and Claude Code may end.
        var sent = 0
        var results = 0
        var step = 0
        var apiError: String? = null
        var finished = false
        // A connection's tools show under their Whittle names, so the chat presents them like Whittle's own.
        val named: (String) -> String = { tool -> handed?.toolId(tool) ?: tool }
        val asking = ConcurrentHashMap<String, Asking>()
        val writing = mutableMapOf<String, Writing>()
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
                            is RunInput.Steer -> say((listOf(input.text) + input.pasted.map { it.text }).joinToString(PARAGRAPH))
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
                        is ClaudeCodeEvent.ToolUse -> {
                            chat?.let { writing(it, event.name, event.input) }?.let { writing[event.id] = it }
                            send(RunEvent.ToolCallDecided(call(event.id, named(event.name), event.input), ALLOWED))
                        }
                        is ClaudeCodeEvent.ToolResult -> {
                            writing.remove(event.id)?.let { write -> if (!event.isError && chat != null) changes.record(chat, write.file, write.before) }
                            send(RunEvent.ToolCallFinished(ContentBlock.ToolResult(ToolCallId(event.id), event.content, event.isError, OUTPUT)))
                        }
                        is ClaudeCodeEvent.PermissionRequest -> {
                            val id = event.toolUseId ?: event.requestId
                            val name = named(event.toolName)
                            val decision = PendingDecision(call(id, name, event.input), ToolId(name), ApprovalScope.EVERY_CALL, step)
                            asking[id] = Asking(event.requestId, event.input, decision)
                            // Claude Code reads files and the web on its own, so Whittle can't tell what steered it: tainted.
                            send(RunEvent.DecisionRequested(decision, definition(name), event.input, tainted = true))
                        }
                        is ClaudeCodeEvent.OtherRequest -> process.write(ClaudeCodeStream.unsupported(event.requestId))
                        is ClaudeCodeEvent.ApiError -> apiError = event.kind
                        is ClaudeCodeEvent.Retrying -> send(RunEvent.ModelRetrying(event.attempt, event.delayMillis))
                        is ClaudeCodeEvent.RateLimit -> if (event.status == REJECTED) {
                            val resetsAt = event.resetsAtSeconds?.let { it * MILLIS_PER_SECOND }
                            onLimit(ClaudeCodeLimit(event.window, resetsAt))
                            // Claude Code holds the task until the window reopens; the line says it's waiting.
                            send(RunEvent.ModelRetrying(attempt = 1, delayMillis = resetsAt?.let { (it - System.currentTimeMillis()).coerceAtLeast(0) } ?: 0))
                        }
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

    /**
     * The file a [tool] call is about to write, with what it holds now, when the tool is one that writes files.
     * Claude Code asks before writing, so this reads the file before the write.
     */
    private suspend fun writing(chat: WorkspaceId, tool: String, input: JsonObject): Writing? {
        val argument = config.fileArguments[tool] ?: return null
        val path = (input[argument] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val file = locate(chat, path) ?: return null
        return Writing(file, previousText(file, maxFileChars))
    }

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

    /** A file a call is writing, and what it held before. */
    private class Writing(val file: File, val before: FileChanges.Before)

    /** A permission Claude Code is waiting on, by the call it's for. */
    private class Asking(val requestId: String, val input: JsonObject, val decision: PendingDecision)

    private companion object {
        val ALLOWED = PolicyDecision.Allow(DecisionReason.Rule("claude-code"))
        /** Claude Code's tools read files and the web; what they return informs, never authorizes. */
        val OUTPUT = Provenance.Untrusted("Claude Code")
        const val PARAGRAPH = "\n\n"
        const val KEY_PREFIX = "key-"
        const val REJECTED = "rejected"
        const val MILLIS_PER_SECOND = 1000L
        const val LIMIT_STATUS = 429
        const val UNKNOWN_STATUS = 0
        const val NO_WORKSPACE = "The workspace isn't available"
        const val STOPPED = "Claude Code stopped before it finished"
        const val NOT_RESUMABLE = "A Claude Code task can't continue after the app closed"
    }
}
