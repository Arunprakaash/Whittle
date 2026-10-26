package app.whittle.design.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Colours taken from the wallpaper (Material You, Android 12+); null where the platform has none. */
@Composable
internal expect fun wallpaperColorScheme(dark: Boolean): ColorScheme?

/** Whether the system lets things move: false when the user turned animations off. */
@Composable
expect fun animationsEnabled(): Boolean
