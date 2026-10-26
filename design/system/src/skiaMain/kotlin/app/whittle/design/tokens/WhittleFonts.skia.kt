package app.whittle.design.tokens

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.platform.Font

/** The same font files as Android; the system's fonts only if a file can't be read. */
actual object WhittleFonts {
    actual val sans: FontFamily = variableFamily(SANS) ?: FontFamily.SansSerif
    actual val mono: FontFamily = variableFamily(MONO) ?: FontFamily.Monospace

    private fun variableFamily(file: String): FontFamily? {
        val bytes = fontFile(file) ?: return null
        return FontFamily(
            WhittleFontWeights.map { weight ->
                Font("$file-${weight.weight}", bytes, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
            },
        )
    }

    /** The bundled font files' names, as Android's resources name them. */
    const val SANS = "figtree.ttf"
    const val MONO = "geist_mono.ttf"
}

/** A bundled font file's bytes; null when it can't be read. */
internal expect fun fontFile(name: String): ByteArray?
