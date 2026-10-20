package app.whittle.design.theme

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import app.whittle.design.tokens.HapticEnvelope
import app.whittle.design.tokens.WhittleHaptic

/** Plays the haptic tokens. Respects the in-app switch and the system touch-feedback setting. */
fun interface WhittleHaptics {
    fun perform(haptic: WhittleHaptic)

    companion object {
        val Off = WhittleHaptics { }
    }
}

@Composable
internal fun rememberWhittleHaptics(enabled: Boolean): WhittleHaptics {
    val feedback = LocalHapticFeedback.current
    val context = LocalContext.current
    return remember(enabled, feedback, context) { if (enabled) SystemHaptics(feedback, context) else WhittleHaptics.Off }
}

private class SystemHaptics(
    private val feedback: HapticFeedback,
    private val context: Context,
) : WhittleHaptics {
    override fun perform(haptic: WhittleHaptic) {
        val played = haptic.envelope != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && playEnvelope(haptic)
        if (!played) feedback.performHapticFeedback(haptic.feedback)
    }

    /** Custom envelopes bypass the view system, so they check the touch-feedback setting themselves. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun playEnvelope(haptic: WhittleHaptic): Boolean {
        val touchFeedbackOn = Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
        val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        if (!touchFeedbackOn || vibrator == null || !vibrator.areEnvelopeEffectsSupported()) return false
        val effect = when (haptic.envelope) {
            HapticEnvelope.Swell -> swell(haptic.pattern)
            null -> return false
        }
        vibrator.vibrate(effect)
        return true
    }

    /** Rises in steps that follow the token's on/off timing, then fades out. */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun swell(pattern: List<Long>): VibrationEffect {
        val pulses = pattern.chunked(2)
        val builder = VibrationEffect.BasicEnvelopeBuilder().setInitialSharpness(SWELL_SHARPNESS)
        pulses.forEachIndexed { index, pulse ->
            val intensity = (index + 1).toFloat() / pulses.size
            builder.addControlPoint(intensity, SWELL_SHARPNESS, pulse.sum())
        }
        builder.addControlPoint(0f, SWELL_SHARPNESS, pulses.last().first())
        return builder.build()
    }

    private companion object {
        /** Mid sharpness: a soft swell rather than a buzz. */
        const val SWELL_SHARPNESS = 0.5f
    }
}
