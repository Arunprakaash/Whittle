package app.whittle.companion

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import app.whittle.chat.ui.ChatAction
import app.whittle.chat.ui.ChatHost
import app.whittle.chat.ui.link.Answer
import app.whittle.chat.ui.link.FromPhone
import app.whittle.chat.ui.link.LinkJson
import app.whittle.chat.ui.link.Question
import app.whittle.chat.ui.link.ToPhone
import io.ktor.server.application.install
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
) {
    private val lock = Mutex()
    private var server: EmbeddedServer<*, *>? = null

    suspend fun start() = lock.withLock {
        if (server != null) return@withLock
        val token = withContext(Dispatchers.IO) { token() } ?: return@withLock
        server = embeddedServer(CIO, host = LOOPBACK, port = config.port) {
            install(WebSockets)
            routing { webSocket(PATH) { serve(token) } }
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
        // The latest state only, at most every updateMillis, so a streaming reply doesn't flood a slow link.
        val sender = launch {
            host.state.collect { state ->
                write(FromPhone.View(state, System.currentTimeMillis()))
                delay(config.updateMillis)
            }
        }
        try {
            for (frame in incoming) {
                when (val message = (frame as? Frame.Text)?.let(::read)) {
                    is ToPhone.Act -> if (allowed(message.action)) withContext(Dispatchers.Main) { host.act(message.action) }
                    is ToPhone.Ask -> launch { write(FromPhone.Reply(message.id, answer(message.question))) }
                    is ToPhone.Hello, null -> Unit
                }
            }
        } finally {
            sender.cancel()
        }
    }

    /** Allow on a capability card is Android's own prompt, on the phone; a computer may only decline. */
    private fun allowed(action: ChatAction): Boolean = !(action is ChatAction.Grant && action.granted)

    private suspend fun answer(question: Question): Answer = when (question) {
        is Question.CreateProject -> Answer.Created(host.createProject(question.name))
        is Question.ProcessOutput -> Answer.Output(host.processOutput(question.key, question.lines))
        is Question.Image -> Answer.Picture(host.image(question.path, question.maxSidePx)?.let { encode(it) })
    }

    private suspend fun encode(image: ImageBitmap): String = withContext(Dispatchers.Default) {
        val bytes = ByteArrayOutputStream().also { image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
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
        const val REFUSED = "not signed in"
        const val MILLIS_PER_SECOND = 1_000L
        const val STOP_GRACE_MILLIS = 500L
        const val STOP_TIMEOUT_MILLIS = 2_000L
        const val JPEG_QUALITY = 85
    }
}
