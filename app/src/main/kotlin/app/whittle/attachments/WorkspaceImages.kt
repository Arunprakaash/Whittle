package app.whittle.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.whittle.agent.ImageSource
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrNull
import app.whittle.core.model.ContentBlock
import app.whittle.settings.AttachmentDefaults
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Attached photos, read from the workspace: for the model, upright and scaled to [AttachmentDefaults.imageMaxSidePx]
 * as JPEG; for the screen, as thumbnails and full views. Both are kept in memory by path and modification time, so
 * a photo that's replaced is read again.
 */
class WorkspaceImages(
    private val workspaces: Workspaces,
    private val defaults: AttachmentDefaults,
) : ImageSource {
    private val forModel = LruCache<String, ContentBlock.Image>(defaults.memoryEntries)
    private val thumbnails = LruCache<String, ImageBitmap>(defaults.memoryEntries)

    override suspend fun load(workspace: WorkspaceId, path: String): Outcome<ContentBlock.Image> = withContext(Dispatchers.IO) {
        val file = when (val resolved = workspaces.resolve(path)) {
            is Outcome.Failure -> return@withContext resolved
            is Outcome.Success -> resolved.value
        }
        val key = key(file)
        forModel.get(key)?.let { return@withContext Outcome.Success(it) }
        val bitmap = decode(file, defaults.imageMaxSidePx) ?: return@withContext Outcome.Failure(HarnessError.Storage("'$path' isn't a readable image"))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, defaults.imageJpegQuality, it) }.toByteArray()
        bitmap.recycle()
        val image = ContentBlock.Image(JPEG, Base64.encodeToString(bytes, Base64.NO_WRAP))
        forModel.put(key, image)
        Outcome.Success(image)
    }

    /** A small upright copy for the composer's tray and the thread; null when the file isn't a readable image. */
    suspend fun thumbnail(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = workspaces.resolve(path).getOrNull() ?: return@withContext null
        val key = key(file)
        thumbnails.get(key) ?: decode(file, defaults.thumbnailSidePx)?.asImageBitmap()?.also { thumbnails.put(key, it) }
    }

    /** The photo for the full-screen viewer, at most [maxSide] pixels on its longer side. */
    suspend fun full(path: String, maxSide: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        workspaces.resolve(path).getOrNull()?.let { decode(it, maxSide) }?.asImageBitmap()
    }

    private fun key(file: File) = "${file.path}@${file.lastModified()}"

    /** Decodes at the smallest sample that still covers [maxSide], then scales and turns it upright in one step. */
    private fun decode(file: File, maxSide: Int): Bitmap? {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation(file))
        }
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { if (it !== decoded) decoded.recycle() }
    }

    /** How far the camera says to turn the photo; mirrored orientations are rare enough to show as shot. */
    private fun rotation(file: File): Float = try {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> QUARTER
            ExifInterface.ORIENTATION_ROTATE_180 -> 2 * QUARTER
            ExifInterface.ORIENTATION_ROTATE_270 -> 3 * QUARTER
            else -> 0f
        }
    } catch (exception: IOException) {
        0f
    }

    private companion object {
        const val JPEG = "image/jpeg"
        const val QUARTER = 90f
    }
}
