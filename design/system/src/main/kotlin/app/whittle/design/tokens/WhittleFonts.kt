package app.whittle.design.tokens

import androidx.annotation.FontRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import app.whittle.design.R

/**
 * Bundled variable fonts (SIL OFL 1.1, licenses in assets/licenses). Each family exposes one instance
 * per weight the type scale uses, set through the font's weight axis rather than synthesized.
 */
object WhittleFonts {
    val sans: FontFamily = variableFamily(R.font.figtree)
    val mono: FontFamily = variableFamily(R.font.geist_mono)

    private fun variableFamily(@FontRes resource: Int): FontFamily = FontFamily(
        WhittleFontWeights.map { weight ->
            Font(resource, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        },
    )
}
