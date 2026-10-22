package app.whittle.files

import app.whittle.core.common.getOrNull
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.FileChanges
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** A file a chat's agent wrote, with what it held before the chat first touched it. */
data class FileChange(
    /** Relative to the workspace. */
    val path: String,
    /** The chat made it; there was nothing before. */
    val created: Boolean,
    /** What it held before the chat's first write; null when it was new, binary or too large to keep. */
    val baseline: String?,
    val lastAtMillis: Long,
    val edits: Int,
) {
    /** Whether Changes can show this file line by line. */
    val comparable: Boolean get() = created || baseline != null
}

/**
 * What each chat's agent changed in the workspace, from the file tools, kept per chat under [directory]. The
 * first write to a file keeps what it held, so Changes shows everything the chat did to it since. Files outside
 * the workspace aren't kept: the file browser can't show them.
 */
class FileChangeLog(
    private val directory: File,
    private val workspaces: Workspaces,
    private val clock: () -> Long = System::currentTimeMillis,
) : FileChanges {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableVersion = MutableStateFlow(0L)

    /** Goes up after every change, so open screens can refresh. */
    val version: StateFlow<Long> = mutableVersion.asStateFlow()

    override suspend fun record(workspace: WorkspaceId, file: File, before: FileChanges.Before) {
        val path = relativePath(file) ?: return
        lock.withLock {
            withContext(Dispatchers.IO) {
                val log = read(workspace)
                val now = clock()
                val entry = log.files[path]?.let { it.copy(lastAtMillis = now, edits = it.edits + 1) } ?: Entry(
                    created = before is FileChanges.Before.Nothing,
                    baseline = (before as? FileChanges.Before.Text)?.content,
                    lastAtMillis = now,
                    edits = 1,
                )
                write(workspace, Log(log.files + (path to entry)))
            }
        }
        mutableVersion.update { it + 1 }
    }

    /** What [chat] changed, most recent first, for files still in the workspace. */
    suspend fun changes(chat: WorkspaceId): List<FileChange> = lock.withLock {
        withContext(Dispatchers.IO) {
            val root = workspaces.folder().getOrNull()
            read(chat).files
                .filter { (path, _) -> root == null || File(root, path).isFile }
                .map { (path, entry) -> FileChange(path, entry.created, entry.baseline, entry.lastAtMillis, entry.edits) }
                .sortedByDescending { it.lastAtMillis }
        }
    }

    /** Forgets what [chat] changed, as when the chat is deleted; the files themselves stay. */
    suspend fun forget(chat: WorkspaceId) {
        lock.withLock { withContext(Dispatchers.IO) { fileOf(chat).delete() } }
        mutableVersion.update { it + 1 }
    }

    private fun relativePath(file: File): String? = try {
        val root = workspaces.folder().getOrNull()?.canonicalFile ?: return null
        val target = file.canonicalFile
        target.takeIf { it.path.startsWith(root.path + File.separator) }?.relativeTo(root)?.invariantSeparatorsPath
    } catch (exception: IOException) {
        null
    }

    private fun read(chat: WorkspaceId): Log = try {
        fileOf(chat).takeIf { it.isFile }?.readText()?.let { json.decodeFromString(Log.serializer(), it) } ?: Log()
    } catch (exception: IOException) {
        Log()
    } catch (exception: SerializationException) {
        // A damaged record only loses what this chat changed; the files are untouched.
        Log()
    }

    private fun write(chat: WorkspaceId, log: Log) {
        try {
            directory.mkdirs()
            val target = fileOf(chat)
            val staging = File(directory, target.name + STAGING)
            staging.writeText(json.encodeToString(Log.serializer(), log))
            if (!staging.renameTo(target)) target.writeText(staging.readText()).also { staging.delete() }
        } catch (exception: IOException) {
            // Recording is best-effort: the write it describes already happened.
        }
    }

    private fun fileOf(chat: WorkspaceId) = File(directory, chat.value + EXTENSION)

    @Serializable
    private data class Log(val files: Map<String, Entry> = emptyMap())

    @Serializable
    private data class Entry(val created: Boolean, val baseline: String? = null, val lastAtMillis: Long, val edits: Int)

    private companion object {
        const val EXTENSION = ".json"
        const val STAGING = ".new"
    }
}
