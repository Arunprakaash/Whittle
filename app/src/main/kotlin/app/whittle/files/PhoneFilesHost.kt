package app.whittle.files

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.whittle.AppGraph
import app.whittle.MainActivity
import app.whittle.R
import app.whittle.core.common.getOrNull
import app.whittle.files.ui.FileContent
import app.whittle.files.ui.FileEntry
import app.whittle.files.ui.FilesHost
import app.whittle.files.ui.textLines
import app.whittle.settings.FilesDefaults
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** The workspace's files as the file browser reads them, on the phone and for the user's computers alike. */
class PhoneFilesHost(private val graph: AppGraph) : FilesHost {
    override val defaults: FilesDefaults get() = graph.filesDefaults

    override val mount: String get() = graph.linux.workspaceMount

    override val version: StateFlow<Long> get() = graph.fileChanges.version

    override suspend fun list(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val folder = graph.workspaces.resolve(path.ifEmpty { CURRENT }).getOrNull() ?: return@withContext emptyList()
        val hidden = defaults.hiddenNames
        folder.listFiles().orEmpty()
            .filter { it.name !in hidden }
            .map { file ->
                FileEntry(
                    name = file.name,
                    path = listOf(path, file.name).filter { it.isNotEmpty() }.joinToString("/"),
                    directory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0L,
                    items = if (file.isDirectory) file.list()?.size ?: 0 else 0,
                )
            }
            .sortedWith(compareBy<FileEntry>({ !it.directory }, { it.name.lowercase() }))
    }

    override suspend fun changes(chat: String): List<FileChange> = graph.fileChanges.changes(WorkspaceId(chat))

    override suspend fun open(path: String): FileContent = withContext(Dispatchers.IO) {
        val file = file(path)?.takeIf { it.isFile } ?: return@withContext FileContent.NotText
        when {
            defaults.isImage(file.name) -> if (bounds(file) != null) FileContent.Picture else FileContent.NotText
            defaults.isPdf(file.name) -> FileContent.Pdf(Pdf.open(file)?.use { it.ratios() }.orEmpty())
            else -> text(file)
        }
    }

    override suspend fun image(path: String, maxSidePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = file(path) ?: return@withContext null
        val (width, height) = bounds(file) ?: return@withContext null
        var sample = 1
        while (maxOf(width, height) / sample > maxSidePx) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }

    override suspend fun pdfPage(path: String, index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        file(path)?.let(Pdf::open)?.use { it.render(index, widthPx) }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) { file(path)?.deleteRecursively() }
    }

    private fun file(path: String): File? = graph.workspaces.resolve(path).getOrNull()

    private fun text(file: File): FileContent = try {
        val buffer = CharArray(defaults.maxPreviewChars + 1)
        val read = file.bufferedReader().use { reader ->
            var total = 0
            while (total < buffer.size) {
                val count = reader.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        val text = String(buffer, 0, minOf(read, defaults.maxPreviewChars))
        if (NUL in text) FileContent.NotText else FileContent.Text(textLines(text), truncated = read > defaults.maxPreviewChars)
    } catch (exception: IOException) {
        FileContent.NotText
    }

    /** A picture's width and height, read without decoding it; null when it isn't one Android can read. */
    private fun bounds(file: File): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        return (options.outWidth to options.outHeight).takeIf { it.first > 0 && it.second > 0 }
    }

    /** An open PDF, read with Android's renderer, which has one page open at a time. */
    private class Pdf(private val descriptor: ParcelFileDescriptor, private val renderer: PdfRenderer) : AutoCloseable {
        /** Width over height of each page. */
        fun ratios(): List<Float> = (0 until renderer.pageCount).map { index ->
            renderer.openPage(index).use { page -> page.width.toFloat() / page.height.coerceAtLeast(1) }
        }

        fun render(index: Int, widthPx: Int): ImageBitmap? {
            if (index !in 0 until renderer.pageCount) return null
            return renderer.openPage(index).use { page ->
                val height = (widthPx.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                // Pages are drawn on white, as paper; a transparent page would take the theme's colour.
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.WHITE) }
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }

        override fun close() {
            renderer.close()
            descriptor.close()
        }

        companion object {
            /** Null for a file that isn't a PDF Android can read, such as one with a password. */
            fun open(file: File): Pdf? {
                val descriptor = try {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                } catch (exception: IOException) {
                    return null
                }
                return try {
                    Pdf(descriptor, PdfRenderer(descriptor))
                } catch (exception: IOException) {
                    descriptor.close()
                    null
                } catch (exception: SecurityException) {
                    descriptor.close()
                    null
                }
            }
        }
    }

    private companion object {
        const val CURRENT = "."
        const val NUL = '\u0000'
    }
}

/** Offers a workspace file to other apps through Android's share sheet. */
fun shareFile(context: Context, path: String, name: String) {
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: FALLBACK_TYPE
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, WorkspaceDocumentsProvider.documentUri(context, path))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        // Whittle takes shares too; sharing a file back into it would only copy it into uploads again.
        val chooser = Intent.createChooser(send, name)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
        context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (exception: ActivityNotFoundException) {
        Toast.makeText(context, R.string.files_unavailable, Toast.LENGTH_SHORT).show()
    }
}

private const val FALLBACK_TYPE = "application/octet-stream"
