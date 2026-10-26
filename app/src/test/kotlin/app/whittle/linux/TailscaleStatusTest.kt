package app.whittle.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TailscaleStatusTest {
    @Test
    fun `connected, with the phone's name and IPv4 address`() {
        val status = TailscaleStatus.parse(
            """{"Version":"1.104.2","BackendState":"Running","AuthURL":"","Self":{"HostName":"whittle-emulator","DNSName":"whittle-emulator.tail1234.ts.net.","TailscaleIPs":["100.119.93.43","fd7a:115c:a1e0::1"]},"Peer":{}}""",
        )!!
        assertTrue(status.running)
        assertEquals("whittle-emulator", status.name)
        assertEquals("100.119.93.43", status.ipv4)
        assertNull(status.authUrl)
    }

    @Test
    fun `waiting for sign-in gives the link`() {
        val status = TailscaleStatus.parse("""{"BackendState":"NeedsLogin","AuthURL":"https://login.tailscale.com/a/dd692ce01a691","Self":{"HostName":"localhost","TailscaleIPs":null}}""")!!
        assertTrue(status.needsLogin)
        assertFalse(status.running)
        assertEquals("https://login.tailscale.com/a/dd692ce01a691", status.authUrl)
        assertNull(status.ipv4)
    }

    @Test
    fun `anything else isn't a status`() {
        assertNull(TailscaleStatus.parse("failed to connect to local tailscaled; it doesn't appear to be running"))
        assertNull(TailscaleStatus.parse("{}"))
        assertNull(TailscaleStatus.parse(""))
    }
}
