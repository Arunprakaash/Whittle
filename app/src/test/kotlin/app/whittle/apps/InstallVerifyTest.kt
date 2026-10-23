package app.whittle.apps

import app.whittle.chat.ThreadItem
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import com.android.apksig.ApkVerifier
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InstallVerifyTest {
    private val directory = Files.createTempDirectory("install-verify").toFile()
    private val keyA = softwareKey("a")
    private val keyB = softwareKey("b")
    private val card = ThreadItem.App("k", "Notes", "app.whittle.made.notes", 1, "notes/build/notes-1.apk", emptyList(), emptyList(), false)

    @Test
    fun `a junk file is refused, not thrown`() {
        val junk = File(directory, "junk.apk").apply { writeText("not a zip") }
        val inspector = ApkInspector { Outcome.Failure(HarnessError.Storage("unreadable")) }
        assertEquals(InstallCheck.CHANGED, verifyCopy(card, junk, File(directory, "copy.apk"), keyA.certificate, inspector, emptyList()))
    }

    @Test
    fun `a missing file is refused, not thrown`() {
        val inspector = ApkInspector { Outcome.Failure(HarnessError.Storage("unreadable")) }
        assertEquals(InstallCheck.CHANGED, verifyCopy(card, File(directory, "absent.apk"), File(directory, "copy2.apk"), keyA.certificate, inspector, emptyList()))
    }

    @Test
    fun `the signer must be the expected one`() {
        val signedByA = signed(keyA, "a.apk")
        assertTrue(signerMatches(verify(signedByA), keyA.certificate))
        assertFalse(signerMatches(verify(signedByA), keyB.certificate))
    }

    @Test
    fun `an unsigned APK matches no signer`() {
        assertFalse(signerMatches(verify(unsignedApk()), keyA.certificate))
    }

    @Test
    fun `install checks against the key Whittle already holds and never makes one`() {
        val apk = signed(keyA, "held.apk")
        val inspector = ApkInspector { Outcome.Success(ApkFacts(card.packageName, 1L, emptyList(), emptyList(), false, 26, 34)) }
        val held = NoMinting(keyA.certificate)
        assertEquals(InstallCheck.OK, verifyForInstall(card, apk, File(directory, "held-copy.apk"), AppSigner(held), inspector, emptyList()))
        val none = NoMinting(null)
        assertEquals(InstallCheck.CHANGED, verifyForInstall(card, apk, File(directory, "none-copy.apk"), AppSigner(none), inspector, emptyList()))
        assertEquals(0, held.minted + none.minted)
    }

    /** Holds [certificate], or nothing; counts any attempt to make a key. */
    private class NoMinting(private val certificate: X509Certificate?) : SigningKeys {
        var minted = 0
        override fun keyFor(packageName: String): Outcome<SigningKey> {
            minted++
            return Outcome.Failure(HarnessError.Storage("must not mint"))
        }
        override fun existingCertificate(packageName: String): X509Certificate? = certificate
    }

    private fun verify(apk: File): ApkVerifier.Result = ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(26).build().verify()

    private fun unsignedApk(): File = File(directory, "unsigned.apk").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.closeEntry()
        }
    }

    private fun signed(key: SigningKey, name: String): File {
        val out = File(directory, name)
        assertIs<Outcome.Success<Unit>>(AppSigner(FakeKeys(Outcome.Success(key))).sign(unsignedApk(), out, "app.whittle.made.notes"))
        return out
    }

    private fun softwareKey(alias: String): SigningKey {
        val store = File(directory, "$alias.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=$alias", "-keystore", store.path, "-storetype", "PKCS12", "-storepass", "changeit",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        val keyStore = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
        return SigningKey(keyStore.getKey(alias, "changeit".toCharArray()) as PrivateKey, assertNotNull(keyStore.getCertificate(alias)) as X509Certificate)
    }
}
