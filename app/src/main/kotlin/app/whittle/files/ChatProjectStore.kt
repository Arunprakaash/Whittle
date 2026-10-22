package app.whittle.files

import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.ChatProjects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Each chat's project, kept in [file]. A choice takes effect at once in memory, so the next command already starts
 * in it; it's written to the phone in [scope] right after.
 */
class ChatProjectStore(private val file: File, private val scope: CoroutineScope) : ChatProjects {
    private val writing = Mutex()
    private val state = MutableStateFlow(read())

    /** Project by chat id. */
    val projects: StateFlow<Map<String, String>> = state.asStateFlow()

    override fun projectOf(chat: WorkspaceId): String? = state.value[chat.value]

    override fun choose(chat: WorkspaceId, project: String?) {
        state.update { if (project == null) it - chat.value else it + (chat.value to project) }
        scope.launch { writing.withLock { write(state.value) } }
    }

    /** The chat is gone; its project, a folder in the workspace, stays. */
    fun forget(chat: WorkspaceId) = choose(chat, null)

    private fun read(): Map<String, String> = try {
        file.takeIf { it.isFile }?.readText()?.let { Json.decodeFromString(SERIALIZER, it) }.orEmpty()
    } catch (exception: IOException) {
        emptyMap()
    } catch (exception: SerializationException) {
        // Losing the choices only sends chats back to the whole workspace.
        emptyMap()
    }

    private fun write(projects: Map<String, String>) {
        try {
            file.parentFile?.mkdirs()
            val staging = File(file.path + STAGING)
            staging.writeText(Json.encodeToString(SERIALIZER, projects))
            if (!staging.renameTo(file)) file.writeText(staging.readText()).also { staging.delete() }
        } catch (exception: IOException) {
            // Kept in memory for this session; the next choice tries again.
        }
    }

    private companion object {
        val SERIALIZER = MapSerializer(String.serializer(), String.serializer())
        const val STAGING = ".new"
    }
}
