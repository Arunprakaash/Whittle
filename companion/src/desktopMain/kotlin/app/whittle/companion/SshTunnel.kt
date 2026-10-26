package app.whittle.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences

/**
 * Opens the way to the phone with this computer's own ssh and key, as the user would in a terminal: reads the token,
 * then keeps `ssh -N -L` running for the link on a free local port, and one more for each chat server opened here. It
 * never asks for a password (the key is added on the phone), takes the phone's host key the first time and refuses a
 * changed one, as ssh's accept-new does.
 */
class SshTunnel(private val phone: PhoneAddress, private val ssh: String = SSH) : PhoneTunnel {
    private val preferences = Preferences.userNodeForPackage(SshTunnel::class.java)
    @Volatile private var tunnel: Process? = null

    /** Where the open tunnel goes (user@host), for the forwards that follow it. */
    @Volatile private var target: String? = null

    /** Each server's forward, by its port on the phone. */
    private val forwards = ConcurrentHashMap<Int, Forward>()

    private class Forward(val local: Int, val process: Process)

    init {
        // However the app ends, short of being killed outright, the tunnel and its forwards end with it.
        Runtime.getRuntime().addShutdownHook(Thread { tunnel?.destroy(); forwards.values.forEach { it.process.destroy() } })
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
        val opened = listen(port, phone.port, target)
        val process = opened.process ?: return@withContext TunnelResult.Failed(opened.said)
        tunnel = process
        this@SshTunnel.target = target
        preferences.put(LAST_HOST, host.trim())
        TunnelResult.Opened("ws://$LOOPBACK:$port/companion", token)
    }

    override suspend fun forward(port: Int): Int? = withContext(Dispatchers.IO) {
        val target = target ?: return@withContext null
        forwards[port]?.let { open ->
            if (open.process.isAlive) return@withContext open.local
            forwards.remove(port)
        }
        // The same port here keeps the page's own links to it working; another when something here already has it.
        val local = if (free(port)) port else ServerSocket(0).use { it.localPort }
        val process = listen(local, port, target).process ?: return@withContext null
        forwards[port] = Forward(local, process)
        local
    }

    override fun close() {
        forwards.values.forEach { it.process.end() }
        forwards.clear()
        tunnel?.end()
        tunnel = null
        target = null
    }

    private fun Process.end() {
        destroy()
        waitFor(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /** What starting a forward came to: ssh, listening, or what it said as it gave up. */
    private class Listening(val process: Process?, val said: String?)

    /** Starts `ssh -N -L` from [local] here to [remote] on the phone; done once something listens here, or ssh gives up. */
    private suspend fun listen(local: Int, remote: Int, target: String): Listening {
        val process = ProcessBuilder(listOf(ssh) + OPTIONS + TUNNEL_OPTIONS + listOf("-p", phone.sshPort.toString(), "-N", "-L", "$local:$LOOPBACK:$remote", target))
            .redirectErrorStream(true)
            .start()
        val listening = withTimeoutOrNull(OPEN_TIMEOUT_MILLIS) {
            while (process.isAlive && !accepts(local)) delay(POLL_MILLIS)
            process.isAlive
        } == true
        if (listening) return Listening(process, null)
        process.destroy()
        val said = process.inputStream.bufferedReader().readText().trim().ifEmpty { null }
        process.waitFor(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
        return Listening(null, said)
    }

    /** Whether nothing on this computer has [port] yet. */
    private fun free(port: Int): Boolean = try {
        ServerSocket(port, 1, InetAddress.getByName(LOOPBACK)).close()
        true
    } catch (_: IOException) {
        false
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
