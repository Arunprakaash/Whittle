package app.whittle.automations

import app.whittle.core.common.ContentCipher
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EncryptedFileAutomationsTest {
    private val base = Files.createTempDirectory("whittle-automations").toFile()
    private val file = File(base, "automations.enc")
    private val aside = File(base, "automations.enc.unreadable")
    private val automation = Automation("a", "Brief", "Brief me", Schedule(420, Schedule.EVERY_DAY), tools = emptySet())

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun `what was saved loads again`() = runTest {
        EncryptedFileAutomations(file, Plain).save(listOf(automation))
        assertEquals(listOf(automation), EncryptedFileAutomations(file, Plain).load())
    }

    @Test
    fun `a file that can't be decrypted is kept aside, not overwritten by the next save`() = runTest {
        EncryptedFileAutomations(file, Plain).save(listOf(automation))
        val written = file.readBytes()
        val store = EncryptedFileAutomations(file, Broken)
        assertTrue(store.load().isEmpty())
        store.save(emptyList())
        assertTrue(written.contentEquals(aside.readBytes()))
    }

    @Test
    fun `a file with a schedule that no longer passes its checks is kept aside`() = runTest {
        file.writeText("""[{"id":"a","name":"Brief","prompt":"Brief me","schedule":{"minuteOfDay":420,"days":[]},"tools":[]}]""")
        assertTrue(EncryptedFileAutomations(file, Plain).load().isEmpty())
        assertTrue(aside.isFile)
        assertTrue(!file.exists())
    }

    private object Plain : ContentCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = Outcome.Success(plaintext)

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = Outcome.Success(ciphertext)
    }

    private object Broken : ContentCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = Outcome.Success(plaintext)

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): Outcome<ByteArray> =
            Outcome.Failure(HarnessError.Storage("the key is gone"))
    }
}
