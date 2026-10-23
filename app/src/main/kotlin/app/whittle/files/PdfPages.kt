package app.whittle.files

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import app.whittle.R
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * A PDF as its pages, one under another, each drawn as it scrolls into view at the screen's width (at most
 * [maxSide] pixels). A file Android can't open shows the not-text fallback instead.
 */
@Composable
fun PdfPages(file: File, name: String, maxSide: Int, modifier: Modifier = Modifier, onShare: () -> Unit) {
    val document = remember(file) { PdfDocument.open(file) }
    DisposableEffect(document) { onDispose { document?.close() } }
    if (document == null || document.pageCount == 0) {
        NotShown(name, onShare, modifier)
        return
    }
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtMost(maxSide)
        LazyColumn(
            contentPadding = PaddingValues(start = WhittleSpace.m, end = WhittleSpace.m, bottom = WhittleSpace.m),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        ) {
            items(document.pageCount) { index ->
                PdfPage(document, index, widthPx, stringResource(R.string.files_pdf_page, index + 1, document.pageCount))
            }
        }
    }
}

@Composable
private fun PdfPage(document: PdfDocument, index: Int, widthPx: Int, label: String) {
    val ratio = remember(document, index) { document.aspectRatio(index) }
    val page by produceState<ImageBitmap?>(null, document, index, widthPx) { value = document.render(index, widthPx) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(WhittleRadius.s))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        page?.let { Image(it, contentDescription = label, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
            ?: Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An open PDF. Android's renderer has one page open at a time, so every page is opened under its lock. */
private class PdfDocument(private val descriptor: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    private var closed = false
    val pageCount: Int get() = renderer.pageCount

    /** Width over height of a page; read once, when the page is first laid out. */
    fun aspectRatio(index: Int): Float = synchronized(renderer) {
        if (closed) return DEFAULT_RATIO
        renderer.openPage(index).use { page -> page.width.toFloat() / page.height.coerceAtLeast(1) }
    }

    suspend fun render(index: Int, widthPx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        synchronized(renderer) {
            if (closed) return@withContext null
            renderer.openPage(index).use { page ->
                val height = (widthPx.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                // Pages are drawn on white, as paper; a transparent page would take the theme's colour.
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).apply { eraseColor(AndroidColor.WHITE) }
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }
    }

    fun close() = synchronized(renderer) {
        closed = true
        renderer.close()
        descriptor.close()
    }

    companion object {
        private const val DEFAULT_RATIO = 0.75f

        /** Null for a file that isn't a PDF Android can read, such as one with a password. */
        fun open(file: File): PdfDocument? {
            val descriptor = try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (exception: IOException) {
                return null
            }
            return try {
                PdfDocument(descriptor, PdfRenderer(descriptor))
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
