package app.whittle.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.whittle.settings.VoiceDefaults
import java.util.Locale
import kotlin.math.min

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
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session: Session? = null

    /** The on-device recognizer failed once (no language pack, model not ready…); later sessions use the phone's default. */
    private var onDeviceFailed = false
    private var retriedBusy = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(listener: VoiceListener) {
        cancel()
        retriedBusy = false
        listen(onDevice = config.preferOnDevice && !onDeviceFailed && onDeviceAvailable(), listener)
    }

    /** Stops listening; what was heard still arrives as the final result. */
    fun stop() {
        val current = session ?: return
        current.stopping = true
        // Between a busy recognizer and its retry there's nothing listening yet to wait for.
        val listening = recognizer ?: return current.giveUp.run()
        listening.stopListening()
        // Some recognizers never answer a stop; the mic mustn't stay open waiting for them.
        main.postDelayed(current.giveUp, config.stopGraceMillis)
    }

    /** Stops at once; nothing more arrives, not even [VoiceListener.onEnd]. */
    fun cancel() {
        session?.let { main.removeCallbacks(it.giveUp) }
        session = null
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
        val next = Session(onDevice, listener)
        recognizer = created
        session = next
        created.setRecognitionListener(next)
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, config.preferOnDevice)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, config.silenceMillis),
        )
    }

    /** One recognizer's run. Callbacks from a run that's been replaced or cancelled are dropped. */
    private inner class Session(private val onDevice: Boolean, private val listener: VoiceListener) : RecognitionListener {
        private val loudness = VoiceLoudness()
        private var heardWords = false
        var stopping = false
        val giveUp = Runnable { finish(null) }

        private val current: Boolean get() = session === this

        override fun onPartialResults(partialResults: Bundle?) {
            if (!current) return
            best(partialResults)?.let { words ->
                heardWords = true
                listener.onPartial(words)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!current) return
            best(results)?.let(listener::onFinal)
            finish(null)
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (current) listener.onLevel(loudness.level(rmsdB))
        }

        override fun onError(error: Int) {
            if (!current) return
            Log.i(TAG, "Recognizer error $error (on device: $onDevice, words heard: $heardWords, stopping: $stopping)")
            val nothingYet = !heardWords && !stopping && error !in QUIET_ENDS && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            // The on-device model may lack this language or not be ready; the phone's default recognizer can still try.
            if (onDevice && nothingYet) {
                onDeviceFailed = true
                cancel()
                listen(onDevice = false, listener)
                return
            }
            // The service was still busy with the last session; once is worth another go.
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && nothingYet && !retriedBusy) {
                retriedBusy = true
                cancel()
                val retry = Session(onDevice, listener)
                session = retry
                main.postDelayed({ if (session === retry) listen(onDevice, listener) }, config.busyRetryMillis)
                return
            }
            finish(
                when {
                    // Whatever was heard is already in the composer; that's a result, not a failure.
                    heardWords -> null
                    error in QUIET_ENDS -> null
                    // Recognizers report the user's own stop as a client error.
                    stopping && error == SpeechRecognizer.ERROR_CLIENT -> null
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.NoPermission
                    error in NETWORK_ERRORS -> VoiceError.Network
                    error in LANGUAGE_ERRORS -> VoiceError.Unavailable
                    else -> VoiceError.Other
                },
            )
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun finish(error: VoiceError?) {
            if (!current) return
            cancel()
            listener.onEnd(error)
        }

        private fun best(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "WhittleVoice"
        val LANGUAGE_ERRORS = setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        val NETWORK_ERRORS = setOf(SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER)

        /** Nothing was heard: listening just ends. */
        val QUIET_ENDS = setOf(SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    }
}

/**
 * Turns the recognizer's loudness reports (dB) into 0 to 1. Phones and rooms differ in how loud silence is, so
 * it counts from the session's own noise floor: steady noise reads as quiet, and the voice rises above it.
 */
class VoiceLoudness(
    /** How far above the floor counts as full voice; recognizers span about 12 dB from silence to loud speech. */
    private val rangeDb: Float = VOICE_RANGE_DB,
    /** How fast the floor rises per report (about ten a second), so one quiet blip doesn't stick. */
    private val creepDb: Float = FLOOR_CREEP_DB,
) {
    private var floor: Float? = null

    fun level(db: Float): Float {
        val quiet = min(floor?.plus(creepDb) ?: db, db)
        floor = quiet
        return ((db - quiet) / rangeDb).coerceIn(0f, 1f)
    }

    private companion object {
        const val VOICE_RANGE_DB = 9f
        const val FLOOR_CREEP_DB = 0.02f
    }
}
