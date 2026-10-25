package app.whittle.linux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComputerKeysTest {
    private val ed = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDRzrEX7LL57tUm4vvi2ULsJPl6dfbBfKwAqC07p1ln6 arun@macbook"
    private val rsa = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDq+YEzuMJMP1QIqlAOCGFOOHxKnnGvb0MYRWqaztMoCVCEfolF4Pw61L2m0Q/jpvWug4fp9QtnBcPuhiOkkVlQLRzIcMzJCcgwiPkY1vYYPLuioQZjsKzq7mPJye++7TrE1oZWyRDbH1paS5Bk/POej1pomOsvmcoBn+1CaeLsDppPe5zvUm2issrrHdDjALIGJGsmZfKBRbhjXHPkeg2OCNghsSjpVX9oqFRFhCp/GoobH6Wkxk6PL3n+YyJEdbaT0J4YQJ7WFG9sMoCqYHx05/nZ3+LF5GlBLeRYNfza4vx0eWgHEPSGqKJiEduS8uIUH3Yf5GGJvdJLb4bM/cbf build@server"
    private val ecdsa = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBERJQUykYYi6cyd3ktJmdJrcCvbU5kGPSC1tngBHfpybcpXt9Q0ccVIyZdrub7U0xIaNA0Q8edWXUvyPVIR7Pic="
    private val types = listOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-rsa")

    private fun added(keys: List<ComputerKey>, pasted: String, max: Int = 10) =
        assertIs<ComputerKeys.Result.Added>(ComputerKeys.add(keys, pasted, types, max))

    private fun refused(keys: List<ComputerKey>, pasted: String, allowed: List<String> = types, max: Int = 10) =
        assertIs<ComputerKeys.Result.Refused>(ComputerKeys.add(keys, pasted, allowed, max)).problem

    @Test
    fun `fingerprints match ssh-keygen`() {
        assertEquals("SHA256:m3Z81C/l/krxwpuk/dlwSm7Z2WwelKVkAa6c+50hJtk", added(emptyList(), ed).key.fingerprint)
        assertEquals("SHA256:py0+E2gtVtxEZdF5qnP2yOZBDLqSjkHWT/pYf3w3fas", added(emptyList(), rsa).key.fingerprint)
        assertEquals("SHA256:7W3ocgJfCoT367AAP8n+7SXvH20ZBgjFXM/th8CHPSs", added(emptyList(), ecdsa).key.fingerprint)
    }

    @Test
    fun `a pasted key is trimmed, named by its comment, and written back as one line`() {
        val result = added(emptyList(), "  $ed\n")
        assertEquals("arun@macbook", result.key.comment)
        assertEquals("$ed\n", ComputerKeys.write(result.keys))
        assertEquals("", added(emptyList(), ecdsa).key.comment)
    }

    @Test
    fun `the keys file reads back, skipping comments, blanks and junk`() {
        val keys = ComputerKeys.parse("# Whittle's computers\n$ed\n\nnot a key\n$rsa\n")
        assertEquals(listOf("arun@macbook", "build@server"), keys.map { it.comment })
        assertEquals(keys, ComputerKeys.parse(ComputerKeys.write(keys)))
    }

    @Test
    fun `anything that isn't one allowed new key is refused`() {
        assertEquals(KeyProblem.NOT_A_KEY, refused(emptyList(), "hello"))
        assertEquals(KeyProblem.NOT_A_KEY, refused(emptyList(), "ssh-ed25519 not-base64!!"))
        assertEquals(KeyProblem.NOT_A_KEY, refused(emptyList(), "$ed\n$rsa"), "one key at a time")
        // The blob names its own type; a key relabelled as another type isn't one.
        assertEquals(KeyProblem.NOT_A_KEY, refused(emptyList(), ed.replace("ssh-ed25519", "ssh-rsa")))
        assertEquals(KeyProblem.NOT_A_KEY, refused(emptyList(), "command=\"rm -rf /\" $ed"), "no authorized_keys options")
        assertEquals(KeyProblem.TYPE_NOT_ALLOWED, refused(emptyList(), rsa, allowed = listOf("ssh-ed25519")))
        val one = added(emptyList(), ed).keys
        assertEquals(KeyProblem.ALREADY_ADDED, refused(one, ed.replace("arun@macbook", "renamed")))
        assertEquals(KeyProblem.TOO_MANY, refused(one, rsa, max = 1))
    }

    @Test
    fun `the same key is the same computer, whatever it's called`() {
        val key = added(emptyList(), ed).key
        val renamed = added(emptyList(), ed.replace("arun@macbook", "laptop")).key
        assertTrue(key.sameKey(renamed))
    }
}
