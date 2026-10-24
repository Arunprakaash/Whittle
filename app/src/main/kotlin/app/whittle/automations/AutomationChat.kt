package app.whittle.automations

import app.whittle.chat.ChatViewModel
import app.whittle.core.model.ContentBlock
import app.whittle.tools.api.ToolId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The chat session, as automations start runs in it. Called on the main thread, where the session lives. */
interface AutomationChat {
    /** Waits for the saved chats to load, so a new chat doesn't land under a restoring one; false if they didn't in time. */
    suspend fun awaitRestored(timeoutMillis: Long): Boolean

    val isRunning: Boolean

    val hasModel: Boolean

    /**
     * Starts [automation] in a new chat and returns its id; null while a task is already running. [pasted] is what
     * started it (an event automation's events), sent beside the prompt as untrusted content.
     */
    suspend fun start(automation: Automation, preApprovedTools: Set<ToolId>, pasted: List<ContentBlock.Text> = emptyList()): String?
}

/** The app's one chat session. */
class SessionAutomationChat(private val chat: ChatViewModel) : AutomationChat {
    override suspend fun awaitRestored(timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) { chat.state.first { !it.restoring } } != null

    override val isRunning: Boolean get() = chat.working

    override val hasModel: Boolean get() = chat.state.value.model != null

    override suspend fun start(automation: Automation, preApprovedTools: Set<ToolId>, pasted: List<ContentBlock.Text>): String? =
        chat.startAutomation(automation.name, automation.prompt, preApprovedTools, automation.project, pasted)?.value
}
