package app.whittle.memory

import app.whittle.agent.Memory
import app.whittle.core.common.ContentCipher
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EncryptedFileMemoryTest {
    private val base = Files.createTempDirectory("whittle-memory").toFile()
    private val file = File(base, "memory.enc")

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun `what was saved loads again`() = runBlocking<Unit> {
        val memory = EncryptedFileMemory(file, Reversible)
        assertIs<Outcome.Success<Unit>>(memory.save(listOf(FACT)))
        assertEquals(listOf(FACT), EncryptedFileMemory(file, Reversible).load())
    }

    @Test
    fun `a file that can't be read is kept aside, never overwritten`() = runBlocking<Unit> {
        EncryptedFileMemory(file, Reversible).save(listOf(FACT))
        val held = file.readBytes()
        // The key changed: nothing decrypts any more.
        val memory = EncryptedFileMemory(file, Refusing)
        assertTrue(memory.load().isEmpty())
        memory.save(emptyList())
        val kept = File(base, "memory.enc.unreadable")
        assertTrue(held.contentEquals(kept.readBytes()), "the unreadable file must survive the next save")
    }

    private object Reversible : ContentCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Outcome<ByteArray> = Outcome.Success(plaintext.reversedArray())

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): Outcome<ByteArray> = Outcome.Success(ciphertext.reversedArray())
    }

    private object Refusing : ContentCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Outcome<ByteArray> = Outcome.Success(plaintext)

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): Outcome<ByteArray> = Outcome.Failure(HarnessError.Storage("bad key"))
    }

    private companion object {
        val FACT = Memory(id = "m1", text = "Drinks tea, not coffee", savedAtMillis = 1_000L)
    }
}
