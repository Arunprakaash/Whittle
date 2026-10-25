package app.whittle.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ComputerAccessConfigTest {
    private fun config(serveCommand: String = "sshd -p {{port}} -o ListenAddress={{address}} -o AuthorizedKeysFile={{keysFile}}", port: Int = 8022) = ComputerAccessConfig(
        schemaVersion = 1, port = port, user = "root", checkCommand = "true", installCommand = "true", installTimeoutSeconds = 1,
        keysFile = "/etc/whittle/computers", serveCommand = serveCommand, startWaitSeconds = 1, keptOutputChars = 100,
        keyTypes = listOf("ssh-ed25519"), maxComputers = 2, printKeyCommand = "cat key.pub", makeKeyCommand = "ssh-keygen",
    )

    @Test
    fun `the server listens on the Wi-Fi address with the computers' keys`() {
        assertEquals("sshd -p 8022 -o ListenAddress=192.168.1.23 -o AuthorizedKeysFile=/etc/whittle/computers", config().serve("192.168.1.23"))
        assertEquals("ssh -p 8022 root@192.168.1.23", config().signIn("192.168.1.23"))
    }

    @Test
    fun `a server command missing a placeholder, or a privileged port, doesn't load`() {
        assertFailsWith<IllegalArgumentException> { config(serveCommand = "sshd -p {{port}}") }
        assertFailsWith<IllegalArgumentException> { config(port = 22) }
    }
}
