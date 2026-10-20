package app.whittle.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * The layer of an exported app's launcher icon: its first letter on a full square whose colour comes from the app.
 * The launcher crops it to its own shape, so the letter stays inside the middle that every shape keeps.
 */
object AppIcon {
    fun png(label: String, seed: Int, sizePx: Int, palette: List<String>): ByteArray {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(palette[Math.floorMod(seed, palette.size)]) }
        canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), fill)
        val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: FALLBACK_LETTER
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * LETTER_FRACTION
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val baseline = sizePx / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(letter, sizePx / 2f, baseline, text)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            out.toByteArray()
        }
    }

    /** Adaptive icons keep the middle 66 of 108 units visible; the letter sits well inside that. */
    private const val LETTER_FRACTION = 0.34f
    private const val FALLBACK_LETTER = "A"
    private const val PNG_QUALITY = 100
}
