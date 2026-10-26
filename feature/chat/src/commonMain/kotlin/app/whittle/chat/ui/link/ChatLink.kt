package app.whittle.chat.ui.link

import app.whittle.chat.ui.ChatAction
import app.whittle.chat.ui.ChatScreenState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The chat screen over a link: a computer shows the phone's chat screen and the phone does what it asks. Each message is
 * one WebSocket text frame. The computer's first is always [ToPhone.Hello].
 */
@Serializable
sealed interface ToPhone {
    /** Proves the computer may see the phone: the token the phone keeps in Linux, which only signed-in computers can read. */
    @Serializable
    @SerialName("hello")
    data class Hello(val token: String) : ToPhone

    @Serializable
    @SerialName("act")
    data class Act(val action: ChatAction) : ToPhone

    /** A question the phone answers with a [FromPhone.Reply] carrying the same [id]. */
    @Serializable
    @SerialName("ask")
    data class Ask(val id: Long, val question: Question) : ToPhone
}

@Serializable
sealed interface FromPhone {
    /**
     * The chat screen as it is now; sent whole whenever it changes. [sentAtMillis] is the phone's clock, so a computer
     * whose clock differs still counts a task's time from when it started on the phone.
     */
    @Serializable
    @SerialName("view")
    data class View(val state: ChatScreenState, val sentAtMillis: Long) : FromPhone

    @Serializable
    @SerialName("reply")
    data class Reply(val id: Long, val answer: Answer) : FromPhone
}

@Serializable
sealed interface Question {
    @Serializable
    @SerialName("create_project")
    data class CreateProject(val name: String) : Question

    @Serializable
    @SerialName("process_output")
    data class ProcessOutput(val key: String, val lines: Int) : Question

    /** A workspace image: a thumbnail when [maxSidePx] is null. */
    @Serializable
    @SerialName("image")
    data class Image(val path: String, val maxSidePx: Int?) : Question
}

@Serializable
sealed interface Answer {
    @Serializable
    @SerialName("created")
    data class Created(val made: Boolean) : Answer

    @Serializable
    @SerialName("output")
    data class Output(val text: String) : Answer

    /** The image, encoded (JPEG or PNG) and in Base64; null when it isn't a readable image. */
    @Serializable
    @SerialName("picture")
    data class Picture(val encoded: String?) : Answer
}

/** The JSON both sides write: unknown fields are let through, so an older computer keeps working with a newer phone. */
val LinkJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "type"
}
