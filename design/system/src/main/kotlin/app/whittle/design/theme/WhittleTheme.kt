package app.whittle.design.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import app.whittle.design.tokens.CreationColors
import app.whittle.design.tokens.CreationTone
import app.whittle.design.tokens.WhittleColors
import app.whittle.design.tokens.WhittleCreationColors
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.tokens.WhittleTextStyles

/** User-facing appearance choices. Defaults come from config, not from here. */
@Immutable
data class WhittleAppearance(
    /** Material You: take colors from the wallpaper on Android 12+. Off keeps Whittle's own palette. */
    val wallpaperColors: Boolean,
    val haptics: Boolean,
)

private val LocalCreationColors = staticCompositionLocalOf { WhittleCreationColors.Light }
private val LocalWhittleHaptics = staticCompositionLocalOf<WhittleHaptics> { WhittleHaptics.Off }

@Composable
fun WhittleTheme(
    appearance: WhittleAppearance,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        appearance.wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> WhittleColors.Dark
        else -> WhittleColors.Light
    }
    CompositionLocalProvider(
        LocalCreationColors provides if (darkTheme) WhittleCreationColors.Dark else WhittleCreationColors.Light,
        LocalWhittleHaptics provides rememberWhittleHaptics(appearance.haptics),
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = WhittleTypography, content = content)
    }
}

/** Whittle values that Material's theme has no slot for. */
object WhittleTheme {
    val creationColors: Map<CreationTone, CreationColors>
        @Composable @ReadOnlyComposable get() = LocalCreationColors.current

    val haptics: WhittleHaptics
        @Composable @ReadOnlyComposable get() = LocalWhittleHaptics.current
}

/** Material slots filled from the type tokens; display styles only swap in the bundled family. */
private val WhittleTypography: Typography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = WhittleFonts.sans),
        displayMedium = base.displayMedium.copy(fontFamily = WhittleFonts.sans),
        displaySmall = base.displaySmall.copy(fontFamily = WhittleFonts.sans),
        headlineLarge = WhittleTextStyles.headlineLarge,
        headlineMedium = WhittleTextStyles.headlineMediumEmphasized,
        headlineSmall = WhittleTextStyles.headlineSmall,
        titleLarge = WhittleTextStyles.titleLarge,
        titleMedium = WhittleTextStyles.titleMedium,
        titleSmall = WhittleTextStyles.titleSmall,
        bodyLarge = WhittleTextStyles.bodyLarge,
        bodyMedium = WhittleTextStyles.bodyMedium,
        bodySmall = WhittleTextStyles.bodySmall,
        labelLarge = WhittleTextStyles.labelLarge,
        labelMedium = WhittleTextStyles.labelMedium,
        labelSmall = WhittleTextStyles.labelSmall,
    )
}
