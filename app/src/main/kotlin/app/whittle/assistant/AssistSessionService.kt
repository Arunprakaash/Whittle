package app.whittle.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import app.whittle.WhittleApplication

/** Creates the session that reads the screen when the user long-presses power or home. */
class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        val app = application as WhittleApplication
        return AssistSession(this, app.graph.assistantDefaults, app.graph.maxPastedOutputChars) { screen -> app.assistIncoming.value = screen }
    }
}
