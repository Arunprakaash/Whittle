package app.whittle.chat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.whittle.design.tokens.WhittleColors
import kotlinx.serialization.Serializable

/**
 * How the phone looks, so a computer looks the same: light or dark as the phone is now, and the colours it uses, its
 * wallpaper's when they're on. Null colours mean Whittle's own palette, which every device has. [dark] is null before
 * the phone has said, when this device's own setting stands in.
 */
@Serializable
data class PhoneLook(
    val dark: Boolean? = null,
    val lightColors: SchemeColors? = null,
    val darkColors: SchemeColors? = null,
) {
    /** The scheme to draw with, light or dark. */
    fun scheme(dark: Boolean): ColorScheme =
        (if (dark) darkColors?.toScheme(WhittleColors.Dark) else lightColors?.toScheme(WhittleColors.Light))
            ?: if (dark) WhittleColors.Dark else WhittleColors.Light
}

/** Whether to draw dark: as the phone is now, or as this device is until the phone says. */
@Composable
fun PhoneLook.isDark(): Boolean = dark ?: isSystemInDarkTheme()

/** A Material colour scheme as ARGB values, to send it over the link. */
@Serializable
data class SchemeColors(
    val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int, val inversePrimary: Int,
    val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
    val background: Int, val onBackground: Int, val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
    val surfaceTint: Int, val inverseSurface: Int, val inverseOnSurface: Int,
    val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
    val outline: Int, val outlineVariant: Int, val scrim: Int,
    val surfaceBright: Int, val surfaceDim: Int, val surfaceContainer: Int, val surfaceContainerHigh: Int,
    val surfaceContainerHighest: Int, val surfaceContainerLow: Int, val surfaceContainerLowest: Int,
) {
    fun toScheme(base: ColorScheme): ColorScheme = base.copy(
        primary = Color(primary), onPrimary = Color(onPrimary), primaryContainer = Color(primaryContainer),
        onPrimaryContainer = Color(onPrimaryContainer), inversePrimary = Color(inversePrimary),
        secondary = Color(secondary), onSecondary = Color(onSecondary), secondaryContainer = Color(secondaryContainer),
        onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary), onTertiary = Color(onTertiary), tertiaryContainer = Color(tertiaryContainer),
        onTertiaryContainer = Color(onTertiaryContainer),
        background = Color(background), onBackground = Color(onBackground), surface = Color(surface), onSurface = Color(onSurface),
        surfaceVariant = Color(surfaceVariant), onSurfaceVariant = Color(onSurfaceVariant),
        surfaceTint = Color(surfaceTint), inverseSurface = Color(inverseSurface), inverseOnSurface = Color(inverseOnSurface),
        error = Color(error), onError = Color(onError), errorContainer = Color(errorContainer), onErrorContainer = Color(onErrorContainer),
        outline = Color(outline), outlineVariant = Color(outlineVariant), scrim = Color(scrim),
        surfaceBright = Color(surfaceBright), surfaceDim = Color(surfaceDim), surfaceContainer = Color(surfaceContainer),
        surfaceContainerHigh = Color(surfaceContainerHigh), surfaceContainerHighest = Color(surfaceContainerHighest),
        surfaceContainerLow = Color(surfaceContainerLow), surfaceContainerLowest = Color(surfaceContainerLowest),
    )

    companion object {
        fun of(scheme: ColorScheme): SchemeColors = with(scheme) {
            SchemeColors(
                primary.toArgb(), onPrimary.toArgb(), primaryContainer.toArgb(), onPrimaryContainer.toArgb(), inversePrimary.toArgb(),
                secondary.toArgb(), onSecondary.toArgb(), secondaryContainer.toArgb(), onSecondaryContainer.toArgb(),
                tertiary.toArgb(), onTertiary.toArgb(), tertiaryContainer.toArgb(), onTertiaryContainer.toArgb(),
                background.toArgb(), onBackground.toArgb(), surface.toArgb(), onSurface.toArgb(), surfaceVariant.toArgb(), onSurfaceVariant.toArgb(),
                surfaceTint.toArgb(), inverseSurface.toArgb(), inverseOnSurface.toArgb(),
                error.toArgb(), onError.toArgb(), errorContainer.toArgb(), onErrorContainer.toArgb(),
                outline.toArgb(), outlineVariant.toArgb(), scrim.toArgb(),
                surfaceBright.toArgb(), surfaceDim.toArgb(), surfaceContainer.toArgb(), surfaceContainerHigh.toArgb(),
                surfaceContainerHighest.toArgb(), surfaceContainerLow.toArgb(), surfaceContainerLowest.toArgb(),
            )
        }
    }
}
