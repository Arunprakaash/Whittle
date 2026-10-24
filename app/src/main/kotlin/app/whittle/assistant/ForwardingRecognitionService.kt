package app.whittle.assistant

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Android makes the assistant app the phone's speech recognizer too. Whittle has no recognizer of its own, so this hands
 * every request to one that is installed (Google's, where there is one) and passes its answers back. Without it, dictation
 * in other apps, and Whittle's own mic, would stop working once Whittle became the assistant.
 *
 * It runs in a process of its own ([PROCESS], see the manifest): every voice-typing tap in any app starts it, and that
 * process skips Whittle's start-up work, and a failure here can't take running tasks or Linux down with it.
 */
class ForwardingRecognitionService : RecognitionService() {
    private var recognizer: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent, callback: Callback) {
        recognizer?.destroy()
        recognizer = null
        val target = target() ?: return tell(callback) { error(SpeechRecognizer.ERROR_CLIENT) }
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

    /**
     * Passes an answer to the app that asked. That app may have died mid-dictation, and then every callback throws
     * (a DeadObjectException): nobody is listening, so the recognizer stops instead of the exception ending the process.
     */
    private fun tell(callback: Callback, answer: Callback.() -> Unit) {
        try {
            callback.answer()
        } catch (exception: RemoteException) {
            recognizer?.cancel()
        }
    }

    private inner class Forwarding(private val callback: Callback) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = tell(callback) { readyForSpeech(params ?: Bundle()) }
        override fun onBeginningOfSpeech() = tell(callback) { beginningOfSpeech() }
        override fun onRmsChanged(rmsdB: Float) = tell(callback) { rmsChanged(rmsdB) }
        override fun onBufferReceived(buffer: ByteArray?) = tell(callback) { bufferReceived(buffer ?: ByteArray(0)) }
        override fun onEndOfSpeech() = tell(callback) { endOfSpeech() }
        override fun onError(error: Int) = tell(callback) { error(error) }
        override fun onResults(results: Bundle?) = tell(callback) { results(results ?: Bundle()) }
        override fun onPartialResults(partialResults: Bundle?) = tell(callback) { partialResults(partialResults ?: Bundle()) }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    companion object {
        /**
         * The process this service runs in, as the manifest names it, with [WhittleAssistantService]: both are started by
         * Android, not the user, and need nothing of Whittle's start-up.
         */
        const val PROCESS = ":recognizer"
        private const val GOOGLE = "com.google."
    }
}
