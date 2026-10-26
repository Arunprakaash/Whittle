package app.whittle.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/**
 * Opens the way to the phone with this computer's own ssh and key, as the user would in a terminal: reads the token,
 * then keeps `ssh -N -L` running for the link on a free local port. It never asks for a password (the key is added
 * on the phone), takes the phone's host key the first time and refuses a changed one, as ssh's accept-new does.
 */
class SshTunnel(private val phone: PhoneAddress, private val ssh: String = SSH) : PhoneTunnel {
    private val preferences = Preferences.userNodeForPackage(SshTunnel::class.java)
    @Volatile private var tunnel: Process? = null

    init {
        // However the app ends, short of being killed outright, the tunnel ends with it.
        Runtime.getRuntime().addShutdownHook(Thread { tunnel?.destroy() })
    }

    override val publicKey: String?
        get() = KEY_FILES.map { File(HOME, it) }.firstOrNull { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    override val makeKeyCommand: String = MAKE_KEY

    override val lastHost: String? get() = preferences.get(LAST_HOST, null)

    override suspend fun open(host: String): TunnelResult = withContext(Dispatchers.IO) {
        close()
        val target = "${phone.user}@${host.trim()}"
        val read = try {
            run(listOf(ssh) + OPTIONS + listOf("-p", phone.sshPort.toString(), target, "cat", phone.tokenFile))
        } catch (_: IOException) {
            return@withContext TunnelResult.NoSsh
        }
        if (read.code != 0) return@withContext problemOf(read)
        val token = read.output.trim().takeIf { it.isNotEmpty() } ?: return@withContext TunnelResult.Failed(read.error)
        val port = ServerSocket(0).use { it.localPort }
        val forward = "$port:$LOOPBACK:${phone.port}"
        val process = ProcessBuilder(listOf(ssh) + OPTIONS + TUNNEL_OPTIONS + listOf("-p", phone.sshPort.toString(), "-N", "-L", forward, target))
            .redirectErrorStream(true)
            .start()
        tunnel = process
        // Open once something listens on the local port, or failed once ssh gives up.
        val listening = withTimeoutOrNull(OPEN_TIMEOUT_MILLIS) {
            while (process.isAlive && !accepts(port)) delay(POLL_MILLIS)
            process.isAlive
        } == true
        if (!listening) {
            val said = process.inputStream.bufferedReader().readText().trim().ifEmpty { null }
            close()
            return@withContext TunnelResult.Failed(said)
        }
        preferences.put(LAST_HOST, host.trim())
        TunnelResult.Opened("ws://$LOOPBACK:$port/companion", token)
    }

    override fun close() {
        tunnel?.let { it.destroy(); it.waitFor(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS) }
        tunnel = null
    }

    private class Ran(val code: Int, val output: String, val error: String?)

    private fun run(command: List<String>): Ran {
        val process = ProcessBuilder(command).start()
        if (!process.waitFor(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return Ran(TIMED_OUT, "", null)
        }
        return Ran(process.exitValue(), process.inputStream.bufferedReader().readText(), process.errorStream.bufferedReader().readText().trim().ifEmpty { null })
    }

    /** What ssh's own words say went wrong. */
    private fun problemOf(ran: Ran): TunnelResult {
        val said = ran.error.orEmpty()
        return when {
            ran.code == TIMED_OUT -> TunnelResult.Unreachable
            HOST_CHANGED in said -> TunnelResult.HostChanged
            DENIED in said -> TunnelResult.NotAdded
            UNREACHABLE.any { it in said } -> TunnelResult.Unreachable
            else -> TunnelResult.Failed(said.lines().lastOrNull { it.isNotBlank() })
        }
    }

    private fun accepts(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), PROBE_MILLIS) }
        true
    } catch (_: IOException) {
        false
    }

    companion object {
        /** The Mac's own ssh. */
        const val SSH = "/usr/bin/ssh"

        private const val LOOPBACK = "127.0.0.1"
        private val HOME = System.getProperty("user.home").orEmpty()

        /** The key files the phone takes, in the order ssh tries them. */
        private val KEY_FILES = listOf(".ssh/id_ed25519.pub")
        private const val MAKE_KEY = "ssh-keygen -t ed25519"

        /** Never a password prompt; the phone's host key taken the first time and checked after; give up in time. */
        private val OPTIONS = listOf("-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=10")

        /** Fail rather than run without the forward; notice a phone that's gone. */
        private val TUNNEL_OPTIONS = listOf("-o", "ExitOnForwardFailure=yes", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3")

        private const val HOST_CHANGED = "REMOTE HOST IDENTIFICATION HAS CHANGED"
        private const val DENIED = "Permission denied"
        private val UNREACHABLE = listOf("Connection refused", "timed out", "No route to host", "Could not resolve", "Network is unreachable")

        private const val LAST_HOST = "lastHost"
        private const val TIMED_OUT = -1
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val OPEN_TIMEOUT_MILLIS = 15_000L
        private const val POLL_MILLIS = 150L
        private const val PROBE_MILLIS = 300
        private const val CLOSE_WAIT_SECONDS = 2L
    }
}
