package app.whittle.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test

class SshTunnelTest {
    /** An ssh that reads the token, and for a tunnel (-N) notes its pid and waits without ever listening. */
    private fun neverListening(dir: File, pids: File): File = File(dir, "ssh").apply {
        writeText(
            """
            #!/bin/sh
            case " $* " in
              *" -N "*) echo $$ >> "${pids.path}"; exec sleep 60 ;;
              *) echo token ;;
            esac
            """.trimIndent() + "\n",
        )
        setExecutable(true)
    }

    @Test
    fun cancellingWhileTheTunnelOpensEndsItsSsh() = runBlocking {
        val dir = createTempDirectory("ssh-tunnel").toFile()
        val pids = File(dir, "pids")
        val tunnel = SshTunnel(PhoneAddress.Default, neverListening(dir, pids).path)
        try {
            val opening = launch(Dispatchers.IO) { tunnel.open("phone") }
            withTimeout(WAIT_MILLIS) { while (pids.readTextOrEmpty().isBlank()) delay(POLL_MILLIS) }
            opening.cancelAndJoin()
            val pid = pids.readText().trim().toLong()
            withTimeout(WAIT_MILLIS) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(POLL_MILLIS) }
        } finally {
            tunnel.close()
            dir.deleteRecursively()
        }
    }

    private fun File.readTextOrEmpty(): String = if (exists()) readText() else ""

    private companion object {
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 50L
    }
}
