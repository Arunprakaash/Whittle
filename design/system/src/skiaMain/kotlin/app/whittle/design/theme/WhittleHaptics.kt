package app.whittle.design.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalHapticFeedback

/** Desktop and the browser have no vibrator: whatever feedback Compose offers there, which is usually none. */
@Composable
internal actual fun rememberWhittleHaptics(enabled: Boolean): WhittleHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(enabled, feedback) { if (enabled) WhittleHaptics { feedback.performHapticFeedback(it.feedback) } else WhittleHaptics.Off }
}
