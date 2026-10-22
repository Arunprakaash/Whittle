package app.whittle.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
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
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Shows the workspace every chat shares in Android's own Files app, as a "Whittle" location. Files can be opened,
 * shared, added, renamed and deleted there. Only the system's document UI, or an app the user shared a file with,
 * can reach this provider (MANAGE_DOCUMENTS, URI grants), and every path is resolved inside the workspace.
 *
 * Document ids: [ROOT] for the workspace, `ROOT/<path>` for anything in it.
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
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addRow(this, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            val hidden = graph.filesDefaults.hiddenNames
            fileFor(parentDocumentId).listFiles().orEmpty().filter { it.name !in hidden }.forEach { addRow(this, "$parentDocumentId/${it.name}") }
            setNotificationUri(requireNotNull(context).contentResolver, childrenUri(parentDocumentId))
        }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileFor(documentId).takeIf { it.isFile } ?: throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
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
        if (documentId == ROOT) throw FileNotFoundException("The workspace can't be renamed")
        val file = fileFor(documentId)
        val target = unusedName(file.parentFile ?: throw FileNotFoundException(documentId), displayName)
        if (!file.renameTo(target)) throw FileNotFoundException("Couldn't rename ${file.name}")
        val parentId = documentId.substringBeforeLast('/')
        changed(parentId)
        return "$parentId/${target.name}"
    }

    override fun deleteDocument(documentId: String) {
        if (documentId == ROOT) throw FileNotFoundException("The workspace can't be deleted")
        if (!fileFor(documentId).deleteRecursively()) throw FileNotFoundException("Couldn't delete $documentId")
        changed(documentId.substringBeforeLast('/'))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId.startsWith("$parentDocumentId/")

    override fun getDocumentType(documentId: String): String =
        if (documentId == ROOT || fileFor(documentId).isDirectory) Document.MIME_TYPE_DIR else mimeType(fileFor(documentId))

    private fun addRow(cursor: MatrixCursor, documentId: String) {
        val context = requireNotNull(context)
        if (documentId == ROOT) {
            cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, ROOT)
                .add(Document.COLUMN_DISPLAY_NAME, context.getString(R.string.app_name))
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
            return
        }
        val file = fileFor(documentId)
        val flags = if (file.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        } else {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else mimeType(file))
            .add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    private fun fileFor(documentId: String): File {
        if (documentId != ROOT && !documentId.startsWith("$ROOT/")) throw FileNotFoundException(documentId)
        val path = documentId.removePrefix(ROOT).removePrefix("/").ifEmpty { CURRENT }
        return graph.workspaces.resolve(path).getOrNull() ?: throw FileNotFoundException(documentId)
    }

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

        /** A file in the workspace, for sharing; the provider resolves it inside the workspace. */
        fun documentUri(context: Context, relativePath: String): Uri =
            DocumentsContract.buildDocumentUri(authority(context), "$ROOT/$relativePath")

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
