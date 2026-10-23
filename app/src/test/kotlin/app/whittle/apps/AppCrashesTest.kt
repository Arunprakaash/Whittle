package app.whittle.apps

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppCrashesTest {
    private val directory = Files.createTempDirectory("app-crashes").toFile()
    private val mine = certificate("mine")
    private val other = certificate("other")
    private val expected = mutableMapOf(NOTES to mine, TIMER to mine)
    private val signers = mutableMapOf<String, X509Certificate>(NOTES to mine, TIMER to mine)
    private val file = File(directory, "crashes.json")

    private fun crashes(max: Int = 100) = AppCrashes({ expected[it] }, { signers[it] }, PREFIX, max, file)

    @Test
    fun `accepts a crash from a Whittle app signed with its key`() {
        val crashes = crashes()
        assertTrue(crashes.accept(NOTES, "boom"))
        assertEquals(mapOf(NOTES to "boom"), crashes.latest.value)
    }

    @Test
    fun `drops an unknown package, a package outside the prefix, and one signed with another key`() {
        val crashes = crashes()
        signers["com.evil"] = mine
        expected["com.evil"] = mine
        signers["app.whittle.made.fake"] = other
        expected["app.whittle.made.fake"] = mine
        assertFalse(crashes.accept(null, "x"))
        assertFalse(crashes.accept("app.whittle.made.unknown", "x"))
        assertFalse(crashes.accept("com.evil", "x"))
        assertFalse(crashes.accept("app.whittle.made.fake", "x"))
        assertFalse(crashes.accept(NOTES, "  "))
        assertTrue(crashes.latest.value.isEmpty())
    }

    @Test
    fun `keeps the last crash per app, capped`() {
        val crashes = crashes(max = 5)
        assertTrue(crashes.accept(NOTES, "first"))
        assertTrue(crashes.accept(NOTES, "0123456789"))
        assertTrue(crashes.accept(TIMER, "t"))
        assertEquals(mapOf(NOTES to "01234", TIMER to "t"), crashes.latest.value)
    }

    @Test
    fun `a crash survives a restart of the process`() {
        crashes().accept(NOTES, "boom")
        assertEquals(mapOf(NOTES to "boom"), crashes().latest.value)
        assertFalse(File(directory, "crashes.json.tmp").exists())
    }

    @Test
    fun `a damaged file starts empty`() {
        file.writeText("{nope")
        assertTrue(crashes().latest.value.isEmpty())
    }

    @Test
    fun `a crash from an in-prefix package with no key is dropped and no key is created`() {
        val keys = RecordingKeys()
        val signer = AppSigner(keys)
        val crashes = AppCrashes(signer::existingCertificate, { mine }, PREFIX, 100, file)
        assertFalse(crashes.accept(NOTES, "boom"))
        assertEquals(listOf("existing:$NOTES"), keys.calls)
        assertTrue(keys.created.isEmpty())
    }

    @Test
    fun `a report must name the caller, the same package, and carry a stack`() {
        val crashes = crashes()
        assertTrue(crashes.report(NOTES, listOf(NOTES), NOTES, "boom"))
        assertFalse(crashes.report(NOTES, listOf(TIMER), NOTES, "again"), "the caller must belong to the calling uid")
        assertFalse(crashes.report(NOTES, listOf(NOTES), TIMER, "again"), "the package column must match")
        assertFalse(crashes.report(NOTES, listOf(NOTES), null, "again"))
        assertFalse(crashes.report(NOTES, listOf(NOTES), NOTES, null))
        assertFalse(crashes.report(NOTES, listOf(NOTES), NOTES, ""))
        assertFalse(crashes.report(NOTES, null, NOTES, "again"))
        assertEquals(mapOf(NOTES to "boom"), crashes.latest.value)
    }

    @Test
    fun `a newer install clears its app's crash, on disk too`() {
        val crashes = crashes()
        crashes.accept(NOTES, "boom")
        crashes.accept(TIMER, "tick")
        crashes.clear(NOTES)
        assertEquals(mapOf(TIMER to "tick"), crashes.latest.value)
        assertEquals(mapOf(TIMER to "tick"), crashes().latest.value)
        crashes.clear("app.whittle.made.absent")
        assertEquals(mapOf(TIMER to "tick"), crashes.latest.value)
    }

    @Test
    fun `a caller is checked by uid, column and prefix before any key is looked at`() {
        assertTrue(isWhittleCaller(NOTES, listOf(NOTES), NOTES, PREFIX))
        assertFalse(isWhittleCaller("com.evil", listOf("com.evil"), "com.evil", PREFIX))
        assertFalse(isWhittleCaller(NOTES, listOf(TIMER), NOTES, PREFIX))
        assertFalse(isWhittleCaller(NOTES, listOf(NOTES), TIMER, PREFIX))
        assertFalse(isWhittleCaller(null, listOf(NOTES), NOTES, PREFIX))
        assertFalse(isWhittleCaller(NOTES, null, NOTES, PREFIX))
    }

    @Test
    fun `the signer is the one certificate of a single signer`() {
        val a = byteArrayOf(1)
        assertContentEquals(a, soleSigner(listOf(a), history = null, multipleSigners = false))
        assertContentEquals(a, soleSigner(listOf(a), history = listOf(a), multipleSigners = false))
        assertNull(soleSigner(listOf(a), history = null, multipleSigners = true))
        assertNull(soleSigner(listOf(a), history = listOf(a, byteArrayOf(2)), multipleSigners = false), "a rotated lineage is refused")
        assertNull(soleSigner(listOf(a, byteArrayOf(2)), history = null, multipleSigners = false))
        assertNull(soleSigner(emptyList(), history = null, multipleSigners = false))
        assertNull(soleSigner(null, history = null, multipleSigners = false))
    }

    @Test
    fun `certificates compare by their encoded bytes`() {
        assertTrue(sameCertificate(mine, assertNotNull(certificateFromBytes(mine.encoded))))
        assertFalse(sameCertificate(mine, other))
    }

    private class RecordingKeys : SigningKeys {
        val calls = mutableListOf<String>()
        val created = mutableListOf<String>()
        override fun keyFor(packageName: String): Outcome<SigningKey> {
            calls += "key:$packageName"
            created += packageName
            return Outcome.Failure(HarnessError.Storage("no"))
        }
        override fun existingCertificate(packageName: String): X509Certificate? {
            calls += "existing:$packageName"
            return null
        }
    }

    private fun certificate(name: String): X509Certificate {
        val store = File(directory, "$name.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=$name", "-keystore", store.path, "-storetype", "PKCS12", "-storepass", "changeit",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        val keyStore = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
        return assertNotNull(keyStore.getCertificate(name)) as X509Certificate
    }

    private companion object {
        const val PREFIX = "app.whittle.made."
        const val NOTES = "app.whittle.made.notes"
        const val TIMER = "app.whittle.made.timer"
    }
}
