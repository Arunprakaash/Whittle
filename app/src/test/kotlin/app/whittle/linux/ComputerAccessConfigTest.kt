package app.whittle.linux

import app.whittle.companion.CompanionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComputerAccessConfigTest {
    private val tailscale = TailscaleConfig(
        checkCommand = "true", installCommand = "true", removeCommand = "true", installTimeoutSeconds = 1,
        daemonCommand = "tailscaled", cli = "tailscale --socket=/run/ts.sock", statusArguments = "status --json",
        signInArguments = "up --hostname={{hostname}}", signOutArguments = "logout", commandTimeoutSeconds = 1,
        markerFile = "/var/lib/tailscale/marker", hostnamePrefix = "whittle-", connectWaitSeconds = 1, pollMillis = 1,
        connectedPollSeconds = 1, keptOutputChars = 10, statusMaxChars = 100,
    )

    private fun config(serveCommand: String = "sshd -p {{port}} {{listen}} -o AuthorizedKeysFile={{keysFile}}", port: Int = 8022) = ComputerAccessConfig(
        schemaVersion = 1, port = port, user = "root", checkCommand = "true", installCommand = "true", installTimeoutSeconds = 1,
        keysFile = "/etc/whittle/computers", serveCommand = serveCommand, startWaitSeconds = 1, keptOutputChars = 100,
        keyTypes = listOf("ssh-ed25519"), maxComputers = 2, printKeyCommand = "cat key.pub", makeKeyCommand = "ssh-keygen",
        companion = CompanionConfig(port = 8790, tokenFile = "/root/token", tokenBytes = 32, helloTimeoutSeconds = 1, updateMillis = 1),
        tailscale = tailscale,
    )

    @Test
    fun `the server listens on the phone, and on Wi-Fi when there is one`() {
        assertEquals(
            "sshd -p 8022 -o ListenAddress=127.0.0.1 -o ListenAddress=192.168.1.23 -o AuthorizedKeysFile=/etc/whittle/computers",
            config().serve("192.168.1.23"),
        )
        // Over Tailscale only: it hands connections to the phone's loopback.
        assertEquals("sshd -p 8022 -o ListenAddress=127.0.0.1 -o AuthorizedKeysFile=/etc/whittle/computers", config().serve(null))
        assertEquals("ssh -p 8022 root@100.101.2.3", config().signIn("100.101.2.3"))
    }

    @Test
    fun `a server command missing a placeholder, or a privileged port, doesn't load`() {
        assertFailsWith<IllegalArgumentException> { config(serveCommand = "sshd -p {{port}} -o ListenAddress={{address}}") }
        assertFailsWith<IllegalArgumentException> { config(port = 22) }
    }

    @Test
    fun `Tailscale commands and the phone's name on the tailnet`() {
        assertEquals("tailscale --socket=/run/ts.sock up --hostname=whittle-pixel-9", tailscale.signIn("whittle-pixel-9"))
        assertEquals("tailscale --socket=/run/ts.sock status --json", tailscale.status())
        assertEquals("whittle-pixel-9-pro", tailscale.hostname("Pixel 9 Pro"))
        assertEquals("whittle-sm-s928b", tailscale.hostname("SM-S928B"))
        assertEquals("whittle-sdk-gphone64-arm64", tailscale.hostname("sdk_gphone64_arm64"))
        assertEquals(63, tailscale.hostname("x".repeat(100)).length)
    }
}
