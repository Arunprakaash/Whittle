package app.whittle.design.theme

import androidx.compose.runtime.Composable
import app.whittle.design.tokens.WhittleHaptic

/** Plays the haptic tokens. Respects the in-app switch and the system touch-feedback setting. */
fun interface WhittleHaptics {
    fun perform(haptic: WhittleHaptic)

    companion object {
        val Off = WhittleHaptics { }
    }
}

/** The platform's haptics when [enabled]; off otherwise. */
@Composable
internal expect fun rememberWhittleHaptics(enabled: Boolean): WhittleHaptics
