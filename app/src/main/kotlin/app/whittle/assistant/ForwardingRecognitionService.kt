package app.whittle.assistant

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android makes the assistant app the phone's speech recognizer too. Whittle has no recognizer of its own, so this hands
 * every request to one that is installed (Google's, where there is one) and passes its answers back. Without it, dictation
 * in other apps, and Whittle's own mic, would stop working once Whittle became the assistant.
 */
class ForwardingRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        recognizer?.destroy()
        val target = target() ?: return callback.error(SpeechRecognizer.ERROR_CLIENT)
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, target).apply {
            setRecognitionListener(Forwarding(callback))
            startListening(intent)
        }
    }

    override fun onStopListening(callback: Callback) {
        recognizer?.stopListening()
    }

    override fun onCancel(callback: Callback) {
        recognizer?.cancel()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    /** Another installed recognizer, Google's first: never this one, which would ask itself. */
    private fun target(): ComponentName? = packageManager.queryIntentServices(Intent(SERVICE_INTERFACE), 0)
        .map { it.serviceInfo }
        .filter { it.packageName != packageName }
        .sortedByDescending { it.packageName.startsWith(GOOGLE) }
        .firstOrNull()
        ?.let { ComponentName(it.packageName, it.name) }

    private class Forwarding(private val callback: Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = callback.readyForSpeech(params ?: Bundle())
        override fun onBeginningOfSpeech() = callback.beginningOfSpeech()
        override fun onRmsChanged(rmsdB: Float) = callback.rmsChanged(rmsdB)
        override fun onBufferReceived(buffer: ByteArray?) = callback.bufferReceived(buffer ?: ByteArray(0))
        override fun onEndOfSpeech() = callback.endOfSpeech()
        override fun onError(error: Int) = callback.error(error)
        override fun onResults(results: Bundle?) = callback.results(results ?: Bundle())
        override fun onPartialResults(partialResults: Bundle?) = callback.partialResults(partialResults ?: Bundle())
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val GOOGLE = "com.google."
    }
}
