package app.whittle.connectors.mcp

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrNull
import app.whittle.core.config.PromptTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Catches a sign-in's return on the phone itself, for servers that only send users back to https or loopback
 * addresses (RFC 8252 §7.3). While a sign-in is open it listens on [base]'s host and port, which must be a
 * loopback address, hands each return to [onReturn], and answers the browser with a small page whose button
 * leads back to Whittle. Other apps on the phone could reach the port too, but the code they'd see is useless
 * without the PKCE verifier, which never leaves Whittle, and the state check rejects anything not started here.
 */
class LoopbackRedirects(
    private val base: HttpUrl,
    private val config: LoopbackSignIn,
    /** The answer page's markup, with title, message, button and link placeholders (connectors/loopback-page.html). */
    private val page: PromptTemplate,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    /** True when a waiting sign-in took the address; it then finishes on its own. */
    private val onReturn: (String) -> Boolean,
) {
    private var listening: Job? = null

    init {
        require(base.isHttps.not() && InetAddress.getByName(base.host).isLoopbackAddress) { "loopback sign-in needs a loopback http address" }
    }

    /** Whether [redirectUri] comes back here rather than through an app link. */
    fun serves(redirectUri: String): Boolean = redirectUri.startsWith(base.toString())

    /** Starts listening, or keeps listening, until a sign-in comes back or [LoopbackSignIn.timeoutMillis] passes. */
    @Synchronized
    fun listen(): Outcome<Unit> {
        if (listening?.isActive == true) return Outcome.Success(Unit)
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName(base.host), base.port))
                soTimeout = POLL_MILLIS
            }
        } catch (exception: IOException) {
            return Outcome.Failure(HarnessError.Network("Couldn't get ready for the sign-in: ${exception.message}"))
        }
        listening = scope.launch(Dispatchers.IO) {
            socket.use {
                val deadline = now() + config.timeoutMillis
                while (isActive && now() < deadline) {
                    val client = try {
                        socket.accept()
                    } catch (timeout: SocketTimeoutException) {
                        continue
                    } catch (exception: IOException) {
                        break
                    }
                    if (client.use(::answer)) break
                }
            }
        }
        return Outcome.Success(Unit)
    }

    /** Reads one request and answers it; true when it finished a sign-in. */
    private fun answer(client: Socket): Boolean {
        client.soTimeout = POLL_MILLIS
        val target = try {
            client.getInputStream().bufferedReader().readLine()?.split(' ')?.getOrNull(1)
        } catch (exception: IOException) {
            null
        }
        val returned = target?.takeIf { it.startsWith(base.encodedPath) }?.let { "${base.scheme}://${base.host}:${base.port}$it" }
        val handled = returned != null && onReturn(returned)
        val status = if (returned == null) NOT_FOUND else OK
        val body = if (handled) {
            render(config.pageTitle, config.pageMessage)
        } else {
            render(config.expiredTitle, config.expiredMessage)
        }
        try {
            client.getOutputStream().write(
                ("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").encodeToByteArray() + body,
            )
        } catch (exception: IOException) {
            // The browser left; the sign-in itself still went through.
        }
        return handled
    }

    private fun render(title: String, message: String): ByteArray {
        val values = mapOf(TITLE to title, MESSAGE to message, BUTTON to config.pageButton, LINK to config.returnUri).mapValues { escape(it.value) }
        return page.render(values).getOrNull().orEmpty().encodeToByteArray()
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private companion object {
        /** How often a quiet socket checks whether to stop. */
        const val POLL_MILLIS = 1_000
        const val OK = "200 OK"
        const val NOT_FOUND = "404 Not Found"
        const val TITLE = "title"
        const val MESSAGE = "message"
        const val BUTTON = "button"
        const val LINK = "link"
    }
}
