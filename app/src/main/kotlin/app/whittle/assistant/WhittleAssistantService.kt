package app.whittle.assistant

import android.service.voice.VoiceInteractionService

/**
 * Whittle as the phone's digital assistant (Settings › Default apps). Android binds this while Whittle holds the role;
 * the work happens in [AssistSession], which [AssistSessionService] creates each time the user calls the assistant.
 */
class WhittleAssistantService : VoiceInteractionService()
