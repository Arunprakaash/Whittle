package app.whittle.companion

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

/** An encoded image (JPEG, PNG) as one to draw; null when it can't be read. */
fun decodeImage(bytes: ByteArray): ImageBitmap? = try {
    Image.makeFromEncoded(bytes).toComposeImageBitmap()
} catch (exception: IllegalArgumentException) {
    null
}
