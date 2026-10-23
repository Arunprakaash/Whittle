package app.whittle.attachments

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrNull
import app.whittle.settings.AttachmentDefaults
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Why a photo or file couldn't be added. */
enum class AttachmentProblem { TOO_MANY, TOO_LARGE, UNREADABLE, NO_WORKSPACE }

sealed interface Imported {
    data class Added(val attachment: Attachment) : Imported

    data class Refused(val problem: AttachmentProblem) : Imported
}

/**
 * Copies what the user picked, shot or shared into the workspace, so the agent can open it with its tools and it
 * stays when the picker's permission ends. It goes to the chat's project's uploads folder, or the workspace's, under
 * a name that never overwrites another file.
 */
class AttachmentImporter(
    private val resolver: ContentResolver,
    private val workspaces: Workspaces,
    private val defaults: AttachmentDefaults,
) {
    /** Saves [uri] in the uploads folder of [project], or of the whole workspace when it's null. */
    suspend fun import(uri: Uri, project: String?): Imported = withContext(Dispatchers.IO) {
        val root = (workspaces.folder() as? Outcome.Success)?.value ?: return@withContext Imported.Refused(AttachmentProblem.NO_WORKSPACE)
        val (displayName, size) = describe(uri)
        if (size != null && size > defaults.maxFileBytes) return@withContext Imported.Refused(AttachmentProblem.TOO_LARGE)
        val mimeType = resolver.getType(uri) ?: mimeTypeOf(displayName) ?: GENERIC_TYPE
        val folder = AttachmentNames.folder(project, defaults.directoryName)
        val directory = File(root, folder)
        val name = AttachmentNames.unique(AttachmentNames.clean(displayName, fallbackName(mimeType))) { File(directory, it).exists() }
        val target = File(directory, name)
        try {
            directory.mkdirs()
            val copied = resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> copyCapped(input, output) }
            } ?: return@withContext Imported.Refused(AttachmentProblem.UNREADABLE)
            if (copied > defaults.maxFileBytes) {
                target.delete()
                return@withContext Imported.Refused(AttachmentProblem.TOO_LARGE)
            }
            Imported.Added(Attachment("$folder/$name", name, copied, mimeType))
        } catch (exception: IOException) {
            target.delete()
            Imported.Refused(AttachmentProblem.UNREADABLE)
        } catch (exception: SecurityException) {
            target.delete()
            Imported.Refused(AttachmentProblem.UNREADABLE)
        }
    }

    /** Deletes an attachment the user took off a message before sending it. */
    suspend fun discard(attachment: Attachment) = withContext(Dispatchers.IO) {
        workspaces.resolve(attachment.path).getOrNull()?.delete()
    }

    /** Copies at most one byte past the limit, so a file that lies about its size still stops early. */
    private fun copyCapped(input: java.io.InputStream, output: java.io.OutputStream): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (total <= defaults.maxFileBytes) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }

    private fun describe(uri: Uri): Pair<String?, Long?> = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null to null
            val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
            val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            name to size
        } ?: (uri.lastPathSegment to null)
    } catch (exception: SecurityException) {
        null to null
    }

    private fun mimeTypeOf(name: String?): String? =
        name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }?.let(MimeTypeMap.getSingleton()::getMimeTypeFromExtension)

    /** A picker that gives no name gets one from the type: "photo.jpg", "file.pdf". */
    private fun fallbackName(mimeType: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val stem = if (mimeType.startsWith(IMAGE_PREFIX)) PHOTO_STEM else FILE_STEM
        return extension?.let { "$stem.$it" } ?: stem
    }

    private companion object {
        const val GENERIC_TYPE = "application/octet-stream"
        const val IMAGE_PREFIX = "image/"
        const val PHOTO_STEM = "photo"
        const val FILE_STEM = "file"
        const val BUFFER_BYTES = 64 * 1024
    }
}
