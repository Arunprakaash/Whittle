package app.whittle.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import app.whittle.R
import app.whittle.WhittleApplication
import app.whittle.core.common.getOrNull
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Shows the chats' workspaces in Android's own Files app, as a "Whittle" location with one folder per chat,
 * named after the chat. Files can be opened, shared, added, renamed and deleted there. Only the system's
 * document UI can reach this provider (MANAGE_DOCUMENTS), and every path is resolved inside its workspace.
 *
 * Document ids: [ROOT] for the location, `ROOT/<workspace>` for a chat's folder, `ROOT/<workspace>/<path>` below.
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {
    private val graph get() = (requireNotNull(context).applicationContext as WhittleApplication).graph

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        val context = requireNotNull(context)
        newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT)
            .add(Root.COLUMN_DOCUMENT_ID, ROOT)
            .add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
            .add(Root.COLUMN_SUMMARY, context.getString(R.string.documents_summary))
            .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addRow(this, documentId, titles()) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (parentDocumentId == ROOT) {
                val titles = titles()
                graph.workspaces.ids().forEach { addRow(this, "$ROOT/${it.value}", titles) }
            } else {
                fileFor(parentDocumentId).listFiles().orEmpty().forEach { addRow(this, "$parentDocumentId/${it.name}", emptyMap()) }
            }
            setNotificationUri(requireNotNull(context).contentResolver, childrenUri(parentDocumentId))
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileFor(documentId).takeIf { it.isFile } ?: throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId == ROOT) throw FileNotFoundException("New folders go inside a chat's folder")
        val parent = fileFor(parentDocumentId).takeIf { it.isDirectory } ?: throw FileNotFoundException(parentDocumentId)
        val file = unusedName(parent, displayName)
        val created = try {
            if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()
        } catch (exception: IOException) {
            false
        }
        if (!created) throw FileNotFoundException("Couldn't create $displayName")
        changed(parentDocumentId)
        return "$parentDocumentId/${file.name}"
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        if (workspaceOf(documentId) == null || isWorkspace(documentId)) throw FileNotFoundException("Only files and folders inside a chat can be renamed")
        val file = fileFor(documentId)
        val target = unusedName(file.parentFile ?: throw FileNotFoundException(documentId), displayName)
        if (!file.renameTo(target)) throw FileNotFoundException("Couldn't rename ${file.name}")
        val parentId = documentId.substringBeforeLast('/')
        changed(parentId)
        return "$parentId/${target.name}"
    }

    /** Deleting a chat's folder removes its files, including apps Whittle built; the chat itself stays. */
    override fun deleteDocument(documentId: String) {
        val workspace = workspaceOf(documentId) ?: throw FileNotFoundException("The Whittle location can't be deleted")
        val deleted = if (isWorkspace(documentId)) {
            graph.workspaces.delete(WorkspaceId(workspace)).getOrNull() != null
        } else {
            fileFor(documentId).deleteRecursively()
        }
        if (!deleted) throw FileNotFoundException("Couldn't delete $documentId")
        changed(documentId.substringBeforeLast('/'))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String =
        if (documentId == ROOT || fileFor(documentId).isDirectory) Document.MIME_TYPE_DIR else mimeType(fileFor(documentId))

    private fun addRow(cursor: MatrixCursor, documentId: String, titles: Map<String, String>) {
        val context = requireNotNull(context)
        if (documentId == ROOT) {
            cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, ROOT)
                .add(Document.COLUMN_DISPLAY_NAME, context.getString(R.string.app_name))
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, 0)
            return
        }
        val file = fileFor(documentId)
        val workspace = isWorkspace(documentId)
        val name = if (workspace) {
            titles[workspaceOf(documentId)].orEmpty().ifEmpty { context.getString(R.string.documents_untitled) }
        } else {
            file.name
        }
        val flags = when {
            workspace -> Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE
            file.isDirectory -> Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            else -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else mimeType(file))
            .add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    /** Chat titles by workspace id; a workspace is named after its chat. */
    private fun titles(): Map<String, String> = runBlocking {
        graph.chats.list().getOrNull().orEmpty().associate { it.id.value to it.title.orEmpty() }
    }

    private fun fileFor(documentId: String): File {
        val workspace = workspaceOf(documentId) ?: throw FileNotFoundException(documentId)
        val path = documentId.removePrefix("$ROOT/$workspace").removePrefix("/").ifEmpty { CURRENT }
        return graph.workspaces.resolve(WorkspaceId(workspace), path).getOrNull() ?: throw FileNotFoundException(documentId)
    }

    private fun workspaceOf(documentId: String): String? =
        documentId.removePrefix("$ROOT/").takeIf { documentId.startsWith("$ROOT/") }?.substringBefore('/')?.ifEmpty { null }

    private fun isWorkspace(documentId: String): Boolean = documentId.count { it == '/' } == 1

    private fun unusedName(parent: File, displayName: String): File {
        val clean = displayName.replace('/', '_').trim().ifEmpty { FALLBACK_NAME }
        val base = clean.substringBeforeLast('.')
        val extension = clean.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return generateSequence(0) { it + 1 }
            .map { if (it == 0) File(parent, clean) else File(parent, "$base ($it)$extension") }
            .first { !it.exists() }
    }

    private fun mimeType(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: FALLBACK_TYPE

    private fun childrenUri(parentDocumentId: String) = DocumentsContract.buildChildDocumentsUri(authority(requireNotNull(context)), parentDocumentId)

    private fun changed(parentDocumentId: String) {
        requireNotNull(context).contentResolver.notifyChange(childrenUri(parentDocumentId), null)
    }

    companion object {
        private const val ROOT = "whittle"
        private const val CURRENT = "."
        private const val FALLBACK_NAME = "file"
        private const val FALLBACK_TYPE = "application/octet-stream"
        private const val AUTHORITY_SUFFIX = ".documents"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        private fun authority(context: Context) = context.packageName + AUTHORITY_SUFFIX

        /** Opens Android's Files app at the Whittle location; false when the phone has no app for it. */
        fun open(context: Context): Boolean {
            val authority = authority(context)
            val atRoot = Intent(Intent.ACTION_VIEW)
                .setDataAndType(DocumentsContract.buildRootUri(authority, ROOT), Root.MIME_TYPE_ITEM)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Phones whose Files app can't be opened at a location still let the user browse from the picker.
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(authority, ROOT))
            return listOf(atRoot, picker).any { intent ->
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (exception: ActivityNotFoundException) {
                    false
                }
            }
        }
    }
}
