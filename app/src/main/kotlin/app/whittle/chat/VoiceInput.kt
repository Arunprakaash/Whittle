package app.whittle.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.whittle.settings.VoiceDefaults
import java.util.Locale

/** What a listening session reports, on the main thread. */
interface VoiceListener {
    fun onPartial(text: String)

    fun onFinal(text: String)

    /** Loudness, 0 to 1, for the mic's ring. */
    fun onLevel(level: Float)

    /** Listening stopped; [error] is set when it failed (no speech heard counts as no error). */
    fun onEnd(error: VoiceError?)
}

enum class VoiceError { NoPermission, Unavailable, Network, Other }

/**
 * Speech to text with Android's own recognizer: on the phone itself when it can (no audio leaves it), else
 * the phone's default recognizer. Words arrive as they're spoken. Use from the main thread.
 */
class VoiceInput(private val context: Context, private val config: VoiceDefaults) {
    private var recognizer: SpeechRecognizer? = null
    private var triedDefault = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: VoiceListener) {
        cancel()
        triedDefault = false
        listen(onDevice = config.preferOnDevice && onDeviceAvailable(), listener)
    }

    /** Stops listening; what was heard still arrives as the final result. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun cancel() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    private fun listen(onDevice: Boolean, listener: VoiceListener) {
        val created = if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = created
        created.setRecognitionListener(Listener(onDevice, listener))
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, config.preferOnDevice)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, config.silenceMillis),
        )
    }

    private inner class Listener(private val onDevice: Boolean, private val listener: VoiceListener) : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let(listener::onPartial)
        }

        override fun onResults(results: Bundle?) {
            best(results)?.let(listener::onFinal)
            listener.onEnd(null)
        }

        override fun onRmsChanged(rmsdB: Float) {
            listener.onLevel(((rmsdB - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0f, 1f))
        }

        override fun onError(error: Int) {
            // The on-device model may lack this language; the phone's default recognizer can still try.
            if (onDevice && !triedDefault && error in LANGUAGE_ERRORS) {
                triedDefault = true
                recognizer?.destroy()
                listen(onDevice = false, listener)
                return
            }
            listener.onEnd(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.NoPermission
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> VoiceError.Network
                    in LANGUAGE_ERRORS -> VoiceError.Unavailable
                    else -> VoiceError.Other
                },
            )
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun best(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        /** The recognizer reports loudness in dB, roughly −2 (silence) to 10 (loud speech). */
        const val QUIET_DB = -2f
        const val LOUD_DB = 10f
        val LANGUAGE_ERRORS = setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
    }
}
