package app.whittle.assistant

import android.service.voice.VoiceInteractionService

/**
 * Whittle as the phone's digital assistant (Settings › Default apps). Android binds this while Whittle holds the role,
 * from boot on, so it runs in the light [ForwardingRecognitionService.PROCESS] process, which skips Whittle's start-up
 * work. The work happens in [AssistSession], which [AssistSessionService] creates in Whittle's own process each time the
 * user calls the assistant.
 */
class WhittleAssistantService : VoiceInteractionService()
