package app.whittle.files

import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.ChatProjects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Each chat's project, kept in [file], and which chats are confined to theirs. A choice takes effect at once in
 * memory, so the next command already starts in it; it's written to the phone in [scope] right after.
 */
class ChatProjectStore(private val file: File, private val scope: CoroutineScope) : ChatProjects {
    private val writing = Mutex()
    private val state = MutableStateFlow(read())

    /** Project by chat id. */
    val projects: StateFlow<Map<String, String>> = state.map { it.projects }.stateIn(scope, SharingStarted.Eagerly, state.value.projects)

    override fun projectOf(chat: WorkspaceId): String? = state.value.projects[chat.value]

    override fun isConfined(chat: WorkspaceId): Boolean = chat.value in state.value.confined

    /** The user's choice, or the agent's in a chat that isn't confined; either way the chat is free of any confinement. */
    override fun choose(chat: WorkspaceId, project: String?) = change(chat, project, confined = false)

    /** An automation's chat: it works in [project] and its file tools reach nothing else, until the user picks another. */
    fun confine(chat: WorkspaceId, project: String) = change(chat, project, confined = true)

    /** The chat is gone; its project, a folder in the workspace, stays. */
    fun forget(chat: WorkspaceId) = choose(chat, null)

    private fun change(chat: WorkspaceId, project: String?, confined: Boolean) {
        state.update {
            Choices(
                projects = if (project == null) it.projects - chat.value else it.projects + (chat.value to project),
                confined = if (confined) it.confined + chat.value else it.confined - chat.value,
            )
        }
        scope.launch { writing.withLock { write(state.value) } }
    }

    private fun read(): Choices {
        val text = try {
            file.takeIf { it.isFile }?.readText() ?: return Choices()
        } catch (exception: IOException) {
            return Choices()
        }
        // Before chats could be confined, the file held only the projects.
        return decode { Choices(projects = Json.decodeFromString(LEGACY, text)) }
            ?: decode { Json.decodeFromString(Choices.serializer(), text) }
            // Losing the choices only sends chats back to the whole workspace.
            ?: Choices()
    }

    private inline fun decode(block: () -> Choices): Choices? = try {
        block()
    } catch (exception: SerializationException) {
        null
    } catch (exception: IllegalArgumentException) {
        null
    }

    private fun write(choices: Choices) {
        try {
            file.parentFile?.mkdirs()
            val staging = File(file.path + STAGING)
            staging.writeText(Json.encodeToString(Choices.serializer(), choices))
            if (!staging.renameTo(file)) file.writeText(staging.readText()).also { staging.delete() }
        } catch (exception: IOException) {
            // Kept in memory for this session; the next choice tries again.
        }
    }

    @Serializable
    private data class Choices(val projects: Map<String, String> = emptyMap(), val confined: Set<String> = emptySet())

    private companion object {
        val LEGACY = MapSerializer(String.serializer(), String.serializer())
        const val STAGING = ".new"
    }
}
