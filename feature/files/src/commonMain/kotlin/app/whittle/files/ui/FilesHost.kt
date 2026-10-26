package app.whittle.files.ui

import androidx.compose.ui.graphics.ImageBitmap
import app.whittle.files.FileChange
import app.whittle.settings.FilesDefaults
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the file browser reads the workspace: the phone's own files on the phone, the same over the link on a computer.
 * Paths are relative to the workspace; "" is the workspace itself.
 */
interface FilesHost {
    /** How files are listed and shown: icons, what's code, how much shows. */
    val defaults: FilesDefaults

    /** The workspace as the agent sees it, such as "/workspace", for the paths the browser shows. */
    val mount: String

    /** Goes up after every change to a file the agent makes, so open screens read again. */
    val version: StateFlow<Long>

    /** A folder's contents, folders first, both by name; Whittle's own files left out. */
    suspend fun list(path: String): List<FileEntry>

    /** What the chat [chat] changed, newest first. */
    suspend fun changes(chat: String): List<FileChange>

    /** What a file shows as. */
    suspend fun open(path: String): FileContent

    /** A picture in the workspace, at most [maxSidePx] on its longer side. */
    suspend fun image(path: String, maxSidePx: Int): ImageBitmap?

    /** Page [index] of a PDF, drawn [widthPx] wide on white. */
    suspend fun pdfPage(path: String, index: Int, widthPx: Int): ImageBitmap?

    suspend fun delete(path: String)
}

/** One thing in a folder. */
@Serializable
data class FileEntry(val name: String, val path: String, val directory: Boolean, val size: Long, val items: Int)

/** What a file shows as. */
@Serializable
sealed interface FileContent {
    @Serializable
    @SerialName("text")
    data class Text(val lines: List<String>, val truncated: Boolean) : FileContent

    @Serializable
    @SerialName("picture")
    data object Picture : FileContent

    /** A PDF as its pages, each by its width over its height. */
    @Serializable
    @SerialName("pdf")
    data class Pdf(val pageRatios: List<Float>) : FileContent

    @Serializable
    @SerialName("not_text")
    data object NotText : FileContent
}

/** A place in the workspace, by its path in it; "" is the workspace itself. */
@Serializable
sealed interface FilesPlace {
    @Serializable
    @SerialName("folder")
    data class Folder(val path: String) : FilesPlace

    @Serializable
    @SerialName("document")
    data class Document(val path: String, val changes: Boolean = false) : FilesPlace
}

/** Opening the browser: where, and the chat it was opened from (its Changed filter and Ask to undo), if any. */
@Serializable
data class FilesOpening(val place: FilesPlace, val chat: String? = null)

/** What the browser does only through the device: other apps' share sheet and file manager. Each null where there's none. */
class FilesPlatform(
    val share: ((path: String, name: String) -> Unit)? = null,
    /** Opens the workspace in the device's file manager; false when it couldn't. */
    val openInFiles: (() -> Boolean)? = null,
    val toast: (String) -> Unit = {},
)
