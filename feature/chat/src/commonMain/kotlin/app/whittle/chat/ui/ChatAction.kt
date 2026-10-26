package app.whittle.chat.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the chat screen asks the phone to do, the same from its own screen or from a computer's. Cards are named by key. */
@Serializable
sealed interface ChatAction {
    @Serializable
    @SerialName("input")
    data class UpdateInput(val text: String) : ChatAction

    /** Sends what's in the composer: starts a task, or adds to the one working. */
    @Serializable
    @SerialName("send")
    data object Send : ChatAction

    /** Sends [text] as the user's message, as if typed: a starter. */
    @Serializable
    @SerialName("send_text")
    data class SendText(val text: String) : ChatAction

    /** Fills the composer with an app idea's words, ready to change. */
    @Serializable
    @SerialName("use_idea")
    data class UseIdea(val prompt: String) : ChatAction

    @Serializable
    @SerialName("composer_started")
    data object ComposerStarted : ChatAction

    @Serializable
    @SerialName("preview_opened")
    data object PreviewOpened : ChatAction

    @Serializable
    @SerialName("answer")
    data class Answer(val key: String, val approve: Boolean) : ChatAction

    @Serializable
    @SerialName("pick")
    data class Pick(val key: String, val option: String) : ChatAction

    /** Allow or Not now on a capability card, once the device has asked for it. */
    @Serializable
    @SerialName("grant")
    data class Grant(val key: String, val granted: Boolean) : ChatAction

    @Serializable
    @SerialName("continue")
    data class Continue(val key: String) : ChatAction

    @Serializable
    @SerialName("stop")
    data object Stop : ChatAction

    @Serializable
    @SerialName("toggle_summary")
    data class ToggleSummary(val key: String) : ChatAction

    @Serializable
    @SerialName("toggle_sources")
    data class ToggleSources(val key: String) : ChatAction

    @Serializable
    @SerialName("new_chat")
    data object NewChat : ChatAction

    @Serializable
    @SerialName("open_chat")
    data class OpenChat(val id: String) : ChatAction

    @Serializable
    @SerialName("pin")
    data class SetPinned(val id: String, val pinned: Boolean) : ChatAction

    @Serializable
    @SerialName("delete")
    data class DeleteChat(val id: String) : ChatAction

    /** Sets the chat's project; null for the whole workspace. */
    @Serializable
    @SerialName("project")
    data class ChooseProject(val project: String?) : ChatAction

    @Serializable
    @SerialName("refresh_projects")
    data object RefreshProjects : ChatAction

    @Serializable
    @SerialName("remove_screen")
    data object RemoveScreen : ChatAction

    @Serializable
    @SerialName("remove_attachment")
    data class RemoveAttachment(val path: String) : ChatAction

    @Serializable
    @SerialName("stop_process")
    data class StopProcess(val key: String) : ChatAction

    @Serializable
    @SerialName("restart_process")
    data class RestartProcess(val key: String) : ChatAction

    /** Ask to fix on a process card: [request] is the user's words, its output goes beside them. */
    @Serializable
    @SerialName("fix_process")
    data class FixProcess(val key: String, val request: String) : ChatAction

    /** Ask to fix on an app card, with its last crash beside [request]. */
    @Serializable
    @SerialName("fix_app")
    data class FixApp(val key: String, val request: String) : ChatAction

    /** Reads again whether the card's app is installed, and which version. */
    @Serializable
    @SerialName("refresh_app")
    data class RefreshApp(val key: String) : ChatAction

    @Serializable
    @SerialName("clear_app_result")
    data class ClearAppResult(val key: String) : ChatAction
}
