package app.whittle.claudecode

import app.whittle.tools.api.WorkspaceId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Which Claude Code session each chat carries on, so the chat's next task continues the same conversation. Only
 * Claude Code's session ids are kept here; the conversation itself stays in Claude Code.
 */
class ClaudeCodeSessions(private val file: File) {
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var sessions: Map<String, String>? = null

    @Synchronized
    fun get(chat: WorkspaceId): String? = loaded()[chat.value]

    @Synchronized
    fun put(chat: WorkspaceId, session: String) {
        if (loaded()[chat.value] == session) return
        write(loaded() + (chat.value to session))
    }

    @Synchronized
    fun forget(chat: WorkspaceId) {
        if (chat.value in loaded()) write(loaded() - chat.value)
    }

    @Synchronized
    fun clear() = write(emptyMap())

    private fun loaded(): Map<String, String> = sessions ?: try {
        if (file.isFile) Json.decodeFromString(serializer, file.readText()) else emptyMap()
    } catch (exception: SerializationException) {
        emptyMap()
    } catch (exception: IOException) {
        emptyMap()
    }.also { sessions = it }

    private fun write(value: Map<String, String>) {
        sessions = value
        try {
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(serializer, value))
        } catch (exception: IOException) {
            // Kept in memory; the chat starts a fresh Claude Code conversation after a restart.
        }
    }
}
