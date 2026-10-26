package app.whittle.companion

import app.whittle.files.ui.nowMillis
import androidx.compose.ui.graphics.ImageBitmap
import app.whittle.chat.ui.ChatAction
import app.whittle.chat.ui.ChatEvent
import app.whittle.chat.ui.ChatHost
import app.whittle.chat.ui.ChatScreenState
import app.whittle.connect.ModelPickerHost
import app.whittle.connect.PickerAction
import app.whittle.connect.PickerState
import app.whittle.chat.ui.ThreadRow
import app.whittle.link.Answer
import app.whittle.link.FromPhone
import app.whittle.link.LinkJson
import app.whittle.link.Question
import app.whittle.link.ToPhone
import app.whittle.files.FileChange
import app.whittle.files.ui.FileContent
import app.whittle.files.ui.FileEntry
import app.whittle.files.ui.FilesHost
import app.whittle.settings.FilesDefaults
import app.whittle.linux.ui.KeyIssue
import app.whittle.linux.ui.LinuxAction
import app.whittle.linux.ui.LinuxHost
import app.whittle.linux.ui.LinuxScreenState
import app.whittle.settings.ui.SettingsAction
import app.whittle.settings.ui.SettingsHost
import app.whittle.settings.ui.SettingsState
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.io.encoding.Base64

/** How the computer stands with the phone. */
sealed interface LinkState {
    /** Not connected; [problem] says why when connecting failed or the phone went away. */
    data class Off(val problem: LinkProblem? = null) : LinkState

    data object Connecting : LinkState

    /** The phone's chat screen is here, live. */
    data object Live : LinkState
}

enum class LinkProblem { Refused, Unreachable, Lost }

/**
 * The phone's chat screen, over one WebSocket: its state comes in as it changes, and what the user does here goes to the
 * phone as the same actions its own screen sends. What's typed shows here at once, before the phone hears it.
 */
class PhoneLink(private val scope: CoroutineScope) : ChatHost {
    private val client = HttpClient { install(WebSockets) }
    private val mutableLink = MutableStateFlow<LinkState>(LinkState.Off())
    val link: StateFlow<LinkState> = mutableLink.asStateFlow()

    private val remote = MutableStateFlow(ChatScreenState())

    /** What's typed here, until something sends or switches chats; then the phone's input again. */
    private val typed = MutableStateFlow<String?>(null)

    override val state: StateFlow<ChatScreenState> = combine(remote, typed) { state, draft -> draft?.let { state.copy(input = it) } ?: state }
        .stateIn(scope, SharingStarted.Eagerly, ChatScreenState())

    // Haptics and toasts are the phone's own; the computer has nothing to feel.
    override val events: Flow<ChatEvent> = emptyFlow()

    private val pickerState = MutableStateFlow(PickerState())

    /** Opens the phone's model picker for this computer; [closePicker] when done. */
    fun openPicker(): ModelPickerHost {
        pickerState.value = PickerState()
        outgoing.trySend(ToPhone.OpenPicker)
        return object : ModelPickerHost {
            override val state: StateFlow<PickerState> = pickerState.asStateFlow()

            override fun act(action: PickerAction) {
                outgoing.trySend(ToPhone.Picker(action))
            }
        }
    }

    fun closePicker() {
        outgoing.trySend(ToPhone.ClosePicker)
    }

    private val filesVersion = MutableStateFlow(0L)
    private val remoteSettings = MutableStateFlow(SettingsState())

    /** The phone's Settings page, changed over the link. */
    val settings: SettingsHost = object : SettingsHost {
        override val state: StateFlow<SettingsState> = remoteSettings.asStateFlow()

        override fun act(action: SettingsAction) {
            outgoing.trySend(ToPhone.Settings(action))
        }
    }

    private val remoteLinux = MutableStateFlow(LinuxScreenState())

    /** The phone's Linux workspace page, changed over the link. */
    val linux: LinuxHost = object : LinuxHost {
        override val state: StateFlow<LinuxScreenState> = remoteLinux.asStateFlow()

        override fun act(action: LinuxAction) {
            outgoing.trySend(ToPhone.Linux(action))
        }

        override suspend fun addComputer(key: String): KeyIssue? = when (val answer = ask(Question.AddComputer(key))) {
            is Answer.KeyAdded -> answer.issue
            else -> KeyIssue.NoAnswer
        }
    }
    private val mutableFiles = MutableStateFlow<FilesHost?>(null)

    /** The phone's workspace for the file browser, once the phone has said how it lists files. */
    val files: StateFlow<FilesHost?> = mutableFiles.asStateFlow()

    private var outgoing = Channel<ToPhone>(Channel.UNLIMITED)
    private val replies = mutableMapOf<Long, CompletableDeferred<Answer>>()
    private var nextQuestion = 0L
    private var job: Job? = null

    fun connect(address: String, token: String) {
        job?.cancel()
        outgoing = Channel(Channel.UNLIMITED)
        val sending = outgoing
        mutableLink.value = LinkState.Connecting
        job = scope.launch {
            try {
                client.webSocket(address) {
                    send(Frame.Text(LinkJson.encodeToString(ToPhone.serializer(), ToPhone.Hello(token))))
                    val sender = launch { for (message in sending) send(Frame.Text(LinkJson.encodeToString(ToPhone.serializer(), message))) }
                    for (frame in incoming) {
                        val text = (frame as? Frame.Text)?.readText() ?: continue
                        when (val message = LinkJson.decodeFromString(FromPhone.serializer(), text)) {
                            is FromPhone.Welcome -> mutableFiles.value = RemoteFiles(message.files, message.mount)
                            is FromPhone.View -> {
                                remote.value = message.state.onThisClock(nowMillis() - message.sentAtMillis)
                                filesVersion.value = message.filesVersion
                                remoteSettings.value = message.settings
                                mutableLink.value = LinkState.Live
                            }
                            is FromPhone.Reply -> replies.remove(message.id)?.complete(message.answer)
                            is FromPhone.Picker -> pickerState.value = message.state
                            is FromPhone.Linux -> remoteLinux.value = message.state
                        }
                    }
                    sender.cancel()
                    val refused = closeReason.await()?.code == CloseReason.Codes.VIOLATED_POLICY.code
                    mutableLink.value = LinkState.Off(if (refused) LinkProblem.Refused else LinkProblem.Lost)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableLink.value = LinkState.Off(if (mutableLink.value == LinkState.Live) LinkProblem.Lost else LinkProblem.Unreachable)
            } finally {
                replies.values.forEach { it.cancel() }
                replies.clear()
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        mutableLink.value = LinkState.Off()
    }

    override fun act(action: ChatAction) {
        when (action) {
            is ChatAction.UpdateInput -> typed.value = action.text
            // The composer empties on the phone; from then on its input is the one shown.
            ChatAction.Send, is ChatAction.SendText, is ChatAction.UseIdea, ChatAction.NewChat, is ChatAction.OpenChat -> typed.value = null
            else -> Unit
        }
        outgoing.trySend(ToPhone.Act(action))
    }

    override suspend fun createProject(name: String): Boolean = (ask(Question.CreateProject(name)) as? Answer.Created)?.made == true

    override suspend fun processOutput(key: String, lines: Int): String = (ask(Question.ProcessOutput(key, lines)) as? Answer.Output)?.text.orEmpty()

    override suspend fun image(path: String, maxSidePx: Int?): ImageBitmap? {
        val encoded = (ask(Question.Image(path, maxSidePx)) as? Answer.Picture)?.encoded ?: return null
        return decodeImage(Base64.decode(encoded))
    }

    /** The phone's workspace, read over the link. */
    private inner class RemoteFiles(override val defaults: FilesDefaults, override val mount: String) : FilesHost {
        override val version: StateFlow<Long> = filesVersion.asStateFlow()

        override suspend fun list(path: String): List<FileEntry> = (ask(Question.ListFolder(path)) as? Answer.Entries)?.entries.orEmpty()

        override suspend fun changes(chat: String): List<FileChange> = (ask(Question.Changes(chat)) as? Answer.Changes)?.changes.orEmpty()

        override suspend fun open(path: String): FileContent = (ask(Question.OpenFile(path)) as? Answer.Content)?.content ?: FileContent.NotText

        override suspend fun image(path: String, maxSidePx: Int): ImageBitmap? = this@PhoneLink.image(path, maxSidePx)

        override suspend fun pdfPage(path: String, index: Int, widthPx: Int): ImageBitmap? =
            (ask(Question.PdfPage(path, index, widthPx)) as? Answer.Picture)?.encoded?.let { decodeImage(Base64.decode(it)) }

        override suspend fun delete(path: String) {
            ask(Question.DeleteFile(path))
        }
    }

    /** Asks the phone; null when it doesn't answer in time or the link drops. */
    private suspend fun ask(question: Question): Answer? {
        val id = nextQuestion++
        val reply = CompletableDeferred<Answer>()
        replies[id] = reply
        outgoing.trySend(ToPhone.Ask(id, question))
        return try {
            withTimeoutOrNull(ANSWER_TIMEOUT_MILLIS) { reply.await() }
        } catch (exception: CancellationException) {
            null
        } finally {
            replies.remove(id)
        }
    }

    private companion object {
        const val ANSWER_TIMEOUT_MILLIS = 15_000L
    }
}

/** The phone's times moved onto this computer's clock, [offset] apart, so elapsed times read right here. */
private fun ChatScreenState.onThisClock(offset: Long): ChatScreenState = copy(
    status = status?.let { it.copy(startedAtMillis = it.startedAtMillis + offset) },
    items = items.map { row ->
        val process = row as? ThreadRow.Process
        val run = process?.run ?: return@map row
        process.copy(run = run.copy(startedAtMillis = run.startedAtMillis + offset, endedAtMillis = run.endedAtMillis?.plus(offset)))
    },
)
