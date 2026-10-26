package app.whittle.design.tokens

import androidx.compose.ui.text.font.FontFamily

/** Bundled variable fonts (SIL OFL 1.1): one instance per weight the type scale uses, set through the weight axis. */
expect object WhittleFonts {
    val sans: FontFamily
    val mono: FontFamily
}
