package app.whittle.companion

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import app.whittle.chat.ui.ChatAction
import app.whittle.chat.ui.ChatHost
import app.whittle.link.Answer
import app.whittle.link.ChatView
import app.whittle.link.FromPhone
import app.whittle.link.JsonPatch
import app.whittle.link.Stream
import app.whittle.linux.ui.LinuxScreenState
import app.whittle.memory.ui.MemoryState
import app.whittle.automations.ui.AutomationsState
import app.whittle.connections.ui.ConnectionsState
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import app.whittle.link.LinkJson
import app.whittle.link.Question
import app.whittle.link.ToPhone
import app.whittle.connect.ModelPickerHost
import app.whittle.files.ui.FilesHost
import app.whittle.linux.ui.LinuxHost
import app.whittle.automations.ui.AutomationsHost
import app.whittle.connections.ui.ConnectionsHost
import app.whittle.memory.ui.MemoryHost
import app.whittle.settings.ui.SettingsHost
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.install
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Serves the phone's chat screen to the user's computers while computer access is on. It listens on the phone's loopback
 * only, so a computer comes in through its own SSH sign-in, and lets in only one that says hello with the token kept at
 * [CompanionConfig.tokenFile] in Linux, which signed-in computers can read and other apps on the phone can't. A computer
 * gets the [host]'s state as it changes and acts through it, as a tap on the phone would.
 */
class CompanionServer(
    private val config: CompanionConfig,
    /** The host file at a Linux path; null before Linux is set up. */
    private val linuxFile: (String) -> File?,
    private val host: ChatHost,
    private val files: FilesHost,
    private val settings: SettingsHost,
    private val linux: LinuxHost,
    private val memory: MemoryHost,
    private val automations: AutomationsHost,
    private val connections: ConnectionsHost,
    /** A model picker for one computer, alive in [scope] until it closes it or leaves. */
    private val picker: (scope: CoroutineScope) -> ModelPickerHost,
    /** A file of the browser version, by its path in the page; null when there's no such file. */
    private val page: (path: String) -> InputStream?,
) {
    private val lock = Mutex()
    private var server: EmbeddedServer<*, *>? = null

    suspend fun start() = lock.withLock {
        if (server != null) return@withLock
        val token = withContext(Dispatchers.IO) { token() } ?: return@withLock
        server = embeddedServer(CIO, host = LOOPBACK, port = config.port) {
            install(WebSockets)
            routing {
                webSocket(PATH) { serve(token) }
                // The browser version, so a computer opens it through the same tunnel; it asks for the token like any other.
                get("/{path...}") { servePage(call.parameters.getAll("path").orEmpty()) }
            }
        }.also { withContext(Dispatchers.IO) { it.start(wait = false) } }
    }

    suspend fun stop() = lock.withLock {
        val running = server ?: return@withLock
        server = null
        withContext(Dispatchers.IO) { running.stop(STOP_GRACE_MILLIS, STOP_TIMEOUT_MILLIS) }
    }

    private suspend fun DefaultWebSocketServerSession.serve(token: String) {
        val hello = withTimeoutOrNull(config.helloTimeoutSeconds * MILLIS_PER_SECOND) { (incoming.receive() as? Frame.Text)?.let(::read) }
        if (hello !is ToPhone.Hello || !MessageDigest.isEqual(hello.token.encodeToByteArray(), token.encodeToByteArray())) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, REFUSED))
            return
        }
        write(FromPhone.Welcome(files.defaults, files.mount))
        // Each screen apart, so setup's progress doesn't resend the chat, and each as changes after the first.
        val chat = combine(host.state, files.version, settings.state) { state, filesVersion, settingsState ->
            ChatView(state, System.currentTimeMillis(), filesVersion, settingsState)
        }
        val senders = listOf(
            stream(Stream.Chat, chat, ChatView.serializer()),
            stream(Stream.Linux, linux.state, LinuxScreenState.serializer()),
            stream(Stream.Memory, memory.state, MemoryState.serializer()),
            stream(Stream.Automations, automations.state, AutomationsState.serializer()),
            stream(Stream.Connections, connections.state, ConnectionsState.serializer()),
            launch { connections.failures.collect { write(FromPhone.ConnectorFailed(it)) } },
        )
        // This computer's model picker, while it has one open.
        var picking: Pair<ModelPickerHost, Job>? = null
        val closePicker = { picking?.second?.cancel(); picking = null }
        try {
            for (frame in incoming) {
                when (val message = (frame as? Frame.Text)?.let(::read)) {
                    is ToPhone.Act -> if (allowed(message.action)) withContext(Dispatchers.Main) { host.act(message.action) }
                    is ToPhone.Settings -> withContext(Dispatchers.Main) { settings.act(message.action) }
                    is ToPhone.Linux -> withContext(Dispatchers.Main) { linux.act(message.action) }
                    is ToPhone.Memory -> withContext(Dispatchers.Main) { memory.act(message.action) }
                    is ToPhone.Automations -> withContext(Dispatchers.Main) { automations.act(message.action) }
                    is ToPhone.Connections -> withContext(Dispatchers.Main) { connections.act(message.action) }
                    ToPhone.OpenPicker -> {
                        closePicker()
                        val job = Job(coroutineContext[Job])
                        val opened = withContext(Dispatchers.Main) { picker(CoroutineScope(coroutineContext + job)) }
                        launch(job) { opened.state.collect { write(FromPhone.Picker(it)) } }
                        picking = opened to job
                    }
                    is ToPhone.Picker -> picking?.first?.let { open -> withContext(Dispatchers.Main) { open.act(message.action) } }
                    ToPhone.ClosePicker -> closePicker()
                    is ToPhone.Ask -> launch { write(FromPhone.Reply(message.id, answer(message.question))) }
                    is ToPhone.Hello, null -> Unit
                }
            }
        } finally {
            closePicker()
            senders.forEach { it.cancel() }
        }
    }

    /**
     * Sends [values] as [stream]: whole the first time, then only what changed, at most every updateMillis, so a reply
     * streaming in doesn't flood a slow link. Each computer has its own, starting whole.
     */
    private fun <T> DefaultWebSocketServerSession.stream(stream: Stream, values: Flow<T>, serializer: KSerializer<T>): Job = launch {
        var sent: JsonElement? = null
        values.collect { value ->
            val now = LinkJson.encodeToJsonElement(serializer, value)
            JsonPatch.diff(sent, now)?.let { write(FromPhone.Changed(stream, it)) }
            sent = now
            delay(config.updateMillis)
        }
    }

    /** One file of the browser version, its index for the bare address; nothing outside the page's own folder. */
    private suspend fun RoutingContext.servePage(segments: List<String>) {
        if (segments.any { it == PARENT || it.startsWith(HIDDEN) }) return call.respond(HttpStatusCode.NotFound)
        val path = segments.joinToString(SEPARATOR).ifEmpty { INDEX }
        val file = withContext(Dispatchers.IO) { page(path) } ?: return call.respond(HttpStatusCode.NotFound)
        val type = if (path.endsWith(WASM_SUFFIX)) WASM else ContentType.defaultForFilePath(path)
        call.respondOutputStream(type) { file.use { it.copyTo(this) } }
    }

    /** Allow on a capability card is Android's own prompt, on the phone; a computer may only decline. */
    private fun allowed(action: ChatAction): Boolean = !(action is ChatAction.Grant && action.granted)

    private suspend fun answer(question: Question): Answer = when (question) {
        is Question.CreateProject -> Answer.Created(host.createProject(question.name))
        is Question.ProcessOutput -> Answer.Output(host.processOutput(question.key, question.lines))
        is Question.Image -> Answer.Picture(host.image(question.path, question.maxSidePx)?.let { encode(it) })
        is Question.ListFolder -> Answer.Entries(files.list(question.path))
        is Question.Changes -> Answer.Changes(files.changes(question.chat))
        is Question.OpenFile -> Answer.Content(files.open(question.path))
        is Question.PdfPage -> Answer.Picture(files.pdfPage(question.path, question.index, question.widthPx)?.let { encode(it, PNG) })
        is Question.DeleteFile -> Answer.Done.also { files.delete(question.path) }
        is Question.AddComputer -> Answer.KeyAdded(linux.addComputer(question.key))
        is Question.SaveMemory -> Answer.MemorySaved(memory.save(question.id, question.text, question.suggestion))
        is Question.SaveAutomation -> Answer.AutomationSaved(automations.save(question.automation))
        is Question.RunAutomation -> Answer.Ran(automations.runNow(question.id))
        is Question.AddLibraryConnection -> Answer.ConnectionAdded(connections.addFromLibrary(question.url))
        is Question.AddConnection -> Answer.ConnectionAdded(connections.add(question.connection, question.keys))
        is Question.ReturnAddress -> Answer.Address(connections.returnAddressFor(question.name))
    }

    /** [image] encoded for the link: JPEG for photos, PNG for pages, whose text JPEG would blur. */
    private suspend fun encode(image: ImageBitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): String = withContext(Dispatchers.Default) {
        val bytes = ByteArrayOutputStream().also { image.asAndroidBitmap().compress(format, JPEG_QUALITY, it) }.toByteArray()
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private suspend fun DefaultWebSocketServerSession.write(message: FromPhone) =
        send(Frame.Text(LinkJson.encodeToString(FromPhone.serializer(), message)))

    private fun read(frame: Frame.Text): ToPhone? = try {
        LinkJson.decodeFromString(ToPhone.serializer(), frame.readText())
    } catch (exception: SerializationException) {
        null
    } catch (exception: IllegalArgumentException) {
        null
    }

    /** The token computers prove themselves with, made the first time; null before Linux is set up. */
    private fun token(): String? {
        val file = linuxFile(config.tokenFile) ?: return null
        return try {
            file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: newToken().also { token ->
                file.parentFile?.mkdirs()
                file.writeText(token + "\n")
                file.setReadable(false, false)
                file.setReadable(true, true)
            }
        } catch (exception: IOException) {
            null
        }
    }

    private fun newToken(): String = ByteArray(config.tokenBytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PATH = "/companion"
        const val INDEX = "index.html"
        const val SEPARATOR = "/"
        const val PARENT = ".."
        const val HIDDEN = "."
        const val WASM_SUFFIX = ".wasm"

        /** Browsers compile WebAssembly as it downloads only when it's served as such. */
        val WASM = ContentType("application", "wasm")
        const val REFUSED = "not signed in"
        const val MILLIS_PER_SECOND = 1_000L
        const val STOP_GRACE_MILLIS = 500L
        const val STOP_TIMEOUT_MILLIS = 2_000L
        const val JPEG_QUALITY = 85
        val PNG = Bitmap.CompressFormat.PNG
    }
}
