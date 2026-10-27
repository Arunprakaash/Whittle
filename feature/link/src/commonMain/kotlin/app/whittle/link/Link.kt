package app.whittle.link

import app.whittle.chat.ui.ChatAction
import app.whittle.chat.ui.ChatScreenState
import app.whittle.connect.PickerAction
import app.whittle.connect.PickerState
import app.whittle.files.FileChange
import app.whittle.files.ui.FileContent
import app.whittle.files.ui.FileEntry
import app.whittle.linux.ui.KeyIssue
import app.whittle.linux.ui.LinuxAction
import app.whittle.linux.ui.LinuxScreenState
import app.whittle.automations.ui.AutomationAction
import app.whittle.automations.ui.AutomationEntry
import app.whittle.automations.ui.AutomationSave
import app.whittle.automations.ui.AutomationsState
import app.whittle.automations.ui.RunNow
import app.whittle.connections.ui.ConnectionAction
import app.whittle.connections.ui.ConnectionAdd
import app.whittle.connections.ui.ConnectionsState
import app.whittle.connections.ui.NewConnection
import app.whittle.memory.ui.MemoryAction
import app.whittle.memory.ui.MemoryIssue
import app.whittle.memory.ui.MemoryState
import app.whittle.settings.FilesDefaults
import app.whittle.settings.ui.SettingsAction
import app.whittle.settings.ui.SettingsState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

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

    /** A change on the Settings page. */
    @Serializable
    @SerialName("settings")
    data class Settings(val action: SettingsAction) : ToPhone

    /** A change on the Linux workspace page. */
    @Serializable
    @SerialName("linux")
    data class Linux(val action: LinuxAction) : ToPhone

    /** A change on the Memory page: forgetting one or everything, dismissing a suggestion, or turning suggestions off. */
    @Serializable
    @SerialName("memory")
    data class Memory(val action: MemoryAction) : ToPhone

    /** A change on the Automations pages: switching one on or off, or deleting it. */
    @Serializable
    @SerialName("automations")
    data class Automations(val action: AutomationAction) : ToPhone

    /** A change to a connection: connecting, renaming, removing, switching its tools. */
    @Serializable
    @SerialName("connections")
    data class Connections(val action: ConnectionAction) : ToPhone

    /** Opens the model picker for this computer: the provider in use, its models listed with its saved key. */
    @Serializable
    @SerialName("open_picker")
    data object OpenPicker : ToPhone

    @Serializable
    @SerialName("picker")
    data class Picker(val action: PickerAction) : ToPhone

    @Serializable
    @SerialName("close_picker")
    data object ClosePicker : ToPhone

    /** A question the phone answers with a [FromPhone.Reply] carrying the same [id]. */
    @Serializable
    @SerialName("ask")
    data class Ask(val id: Long, val question: Question) : ToPhone
}

@Serializable
sealed interface FromPhone {
    /** Sent once the computer is let in: how the file browser lists and shows files, and where the workspace is. */
    @Serializable
    @SerialName("welcome")
    data class Welcome(val files: FilesDefaults, val mount: String) : FromPhone

    /**
     * What changed on one of the phone's screens, as a [JsonPatch] to the [stream]'s value: the first one whole, then
     * only the changes, so a reply streaming in sends its new words and not the chat.
     */
    @Serializable
    @SerialName("changed")
    data class Changed(val stream: Stream, val patch: JsonElement) : FromPhone

    @Serializable
    @SerialName("reply")
    data class Reply(val id: Long, val answer: Answer) : FromPhone





    /** Connecting a service failed, and the service's words for why. */
    @Serializable
    @SerialName("connector_failed")
    data class ConnectorFailed(val reason: String) : FromPhone

    /** The model picker this computer opened, as it is now. */
    @Serializable
    @SerialName("picker")
    data class Picker(val state: PickerState) : FromPhone
}

/** The phone's screens a computer follows, each its own value and patched apart from the others. */
@Serializable
enum class Stream { Chat, Linux, Memory, Automations, Connections }

/**
 * The chat screen and Settings as they are now. [sentAtMillis] is the phone's clock, so a computer whose clock differs
 * still counts a task's time from when it started on the phone.
 */
@Serializable
data class ChatView(val state: ChatScreenState, val sentAtMillis: Long, val filesVersion: Long = 0, val settings: SettingsState = SettingsState())

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

    @Serializable
    @SerialName("list_folder")
    data class ListFolder(val path: String) : Question

    @Serializable
    @SerialName("changes")
    data class Changes(val chat: String) : Question

    @Serializable
    @SerialName("open_file")
    data class OpenFile(val path: String) : Question

    @Serializable
    @SerialName("pdf_page")
    data class PdfPage(val path: String, val index: Int, val widthPx: Int) : Question

    @Serializable
    @SerialName("delete_file")
    data class DeleteFile(val path: String) : Question

    /** Lets a computer sign in with the public key [key]. */
    @Serializable
    @SerialName("add_computer")
    data class AddComputer(val key: String) : Question

    /** Adds a server from the library, by its address. */
    @Serializable
    @SerialName("add_library_connection")
    data class AddLibraryConnection(val url: String) : Question

    /** Adds any server by its address, connecting it with [keys] when it signs in with them. */
    @Serializable
    @SerialName("add_connection")
    data class AddConnection(val connection: NewConnection, val keys: Map<String, String>) : Question

    /** Where a connection named [name] would have its sign-in return. */
    @Serializable
    @SerialName("return_address")
    data class ReturnAddress(val name: String) : Question

    /** Stores an automation, new or changed. */
    @Serializable
    @SerialName("save_automation")
    data class SaveAutomation(val automation: AutomationEntry) : Question

    /** Runs the automation with [id] now, in a new chat. */
    @Serializable
    @SerialName("run_automation")
    data class RunAutomation(val id: String) : Question

    /** Saves [text] as a new memory, or in place of the one with [id]; with [suggestion], keeps that suggestion in these words. */
    @Serializable
    @SerialName("save_memory")
    data class SaveMemory(val id: String?, val text: String, val suggestion: String? = null) : Question
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

    @Serializable
    @SerialName("entries")
    data class Entries(val entries: List<FileEntry>) : Answer

    @Serializable
    @SerialName("file_changes")
    data class Changes(val changes: List<FileChange>) : Answer

    @Serializable
    @SerialName("content")
    data class Content(val content: FileContent) : Answer

    @Serializable
    @SerialName("done")
    data object Done : Answer

    /** The connection was added, or why not. */
    @Serializable
    @SerialName("connection_added")
    data class ConnectionAdded(val result: ConnectionAdd) : Answer

    @Serializable
    @SerialName("address")
    data class Address(val address: String) : Answer

    /** What was stored, or why not. */
    @Serializable
    @SerialName("automation_saved")
    data class AutomationSaved(val result: AutomationSave) : Answer

    /** Whether the run started. */
    @Serializable
    @SerialName("ran")
    data class Ran(val result: RunNow) : Answer

    /** The memory was saved, or why not. */
    @Serializable
    @SerialName("memory_saved")
    data class MemorySaved(val issue: MemoryIssue? = null) : Answer

    /** The key was added, or why not. */
    @Serializable
    @SerialName("key_added")
    data class KeyAdded(val issue: KeyIssue? = null) : Answer
}

/** The JSON both sides write: unknown fields are let through, so an older computer keeps working with a newer phone. */
val LinkJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "type"
}
