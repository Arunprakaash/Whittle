package app.whittle.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/** A launcher icon for an exported app: its first letter on a rounded square whose colour comes from the app. */
object AppIcon {
    fun png(label: String, seed: Int, sizePx: Int, palette: List<String>): ByteArray {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(palette[Math.floorMod(seed, palette.size)]) }
        val corner = sizePx * CORNER_FRACTION
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), corner, corner, fill)
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

    private const val CORNER_FRACTION = 0.22f
    private const val LETTER_FRACTION = 0.5f
    private const val FALLBACK_LETTER = "A"
    private const val PNG_QUALITY = 100
}
