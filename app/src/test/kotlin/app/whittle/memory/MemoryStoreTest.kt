package app.whittle.memory

import app.whittle.core.common.ContentCipher
import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.settings.MemoryDefaults
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryStoreTest {
    /** Reverses the bytes, so a test can tell the file isn't plain text. */
    private val cipher = object : ContentCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = Outcome.Success(plaintext.reversedArray())

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = Outcome.Success(ciphertext.reversedArray())
    }
    private val file = File(Files.createTempDirectory("memory").toFile(), "memory.bin")
    private var next = 0
    private val ids = IdGenerator { "id-${next++}" }

    private fun store(scope: kotlinx.coroutines.CoroutineScope, maxItems: Int = 2) =
        MemoryStore(file, cipher, EpochClock { 7L }, ids, MemoryDefaults("memory.bin", maxItems = maxItems, maxItemChars = 40), scope)

    @Test
    fun `facts are kept encrypted, survive a restart and can be forgotten`() = runTest {
        val memory = store(this)
        val saved = assertIs<Outcome.Success<Memory>>(memory.remember("  Prefers   metric units ")).value
        assertEquals("Prefers metric units", saved.text)
        assertFalse("metric" in file.readText())

        val reopened = store(this)
        // Any change waits for the file to load first.
        assertEquals(Outcome.Success(false), reopened.forget("unknown"))
        assertEquals(listOf(saved), reopened.all.value)
        assertEquals(Outcome.Success(true), reopened.forget(saved.id))
        assertEquals(Outcome.Success(false), reopened.forget(saved.id))
    }

    @Test
    fun `the same fact isn't kept twice, and a full or overlong memory says why`() = runTest {
        val memory = store(this)
        val first = assertIs<Outcome.Success<Memory>>(memory.remember("Lives in Chennai")).value
        assertEquals(first, assertIs<Outcome.Success<Memory>>(memory.remember("lives in chennai")).value)
        memory.remember("Has a dog")

        assertTrue("full" in assertIs<Outcome.Failure>(memory.remember("Likes tea")).error.message)
        assertTrue("characters" in assertIs<Outcome.Failure>(store(this, maxItems = 9).remember("x".repeat(41))).error.message)
        assertEquals(2, memory.all.value.size)
    }
}
