package app.whittle.tools.workspace

import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Hears about every file the file tools write, with what it held before, so the app can show what the agent
 * changed in a chat. Called after the write succeeded; it must not throw.
 */
fun interface FileChanges {
    suspend fun record(workspace: WorkspaceId, file: File, before: Before)

    /** What the file held before the write. */
    sealed interface Before {
        /** The file didn't exist. */
        data object Nothing : Before

        data class Text(val content: String) : Before

        /** It existed, but as a binary or a file too large to keep. */
        data object Unreadable : Before
    }

    companion object {
        val NONE = FileChanges { _, _, _ -> }
    }
}

/** What [file] holds now, as [FileChanges.Before], keeping text up to [maxChars]. */
suspend fun previousText(file: File, maxChars: Int): FileChanges.Before = withContext(Dispatchers.IO) {
    when {
        !file.isFile -> FileChanges.Before.Nothing
        file.length() > maxChars.toLong() * MAX_BYTES_PER_CHAR -> FileChanges.Before.Unreadable
        else -> try {
            file.readText().takeIf { it.length <= maxChars && NUL !in it }?.let { FileChanges.Before.Text(it) } ?: FileChanges.Before.Unreadable
        } catch (exception: IOException) {
            FileChanges.Before.Unreadable
        }
    }
}

private const val NUL = '\u0000'
/** UTF-8 takes at most four bytes per character; a larger file can't be within the character limit. */
private const val MAX_BYTES_PER_CHAR = 4
