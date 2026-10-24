package app.whittle.apps

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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSignerTest {
    private val directory = Files.createTempDirectory("app-signer").toFile()
    private val fake = softwareKey()
    private val signer = AppSigner(FakeKeys(Outcome.Success(fake)))

    @Test
    fun `signs with v2 and the APK verifies`() {
        val unsigned = unsignedApk()
        val signed = File(directory, "signed.apk")

        assertIs<Outcome.Success<Unit>>(signer.sign(unsigned, signed, PACKAGE))

        val result = ApkVerifier.Builder(signed)
            .setMinCheckedPlatformVersion(26)
            .setMaxCheckedPlatformVersion(36)
            .build()
            .verify()
        assertTrue(result.isVerified, result.errors.joinToString())
        assertTrue(result.isVerifiedUsingV2Scheme)
        assertEquals(listOf(fake.certificate), result.signerCertificates)
    }

    @Test
    fun `reports the certificate that signs a package`() {
        assertEquals(fake.certificate, signer.existingCertificate(PACKAGE))
    }

    @Test
    fun `fails without touching the output when there is no key`() {
        val refusing = AppSigner(FakeKeys(Outcome.Failure(app.whittle.core.common.HarnessError.Storage("no key"))))
        val signed = File(directory, "refused.apk")

        assertIs<Outcome.Failure>(refusing.sign(unsignedApk(), signed, PACKAGE))
        assertTrue(!signed.exists())
        assertNull(refusing.existingCertificate(PACKAGE))
    }

    @Test
    fun `fails and leaves no output when the input is not an APK`() {
        val notAnApk = File(directory, "garbage.apk").apply { writeText("not a zip") }
        val signed = File(directory, "garbage-signed.apk")

        assertIs<Outcome.Failure>(signer.sign(notAnApk, signed, PACKAGE))
        assertTrue(!signed.exists())
    }

    private fun unsignedApk(): File = File(directory, "unsigned.apk").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.closeEntry()
        }
    }

    /** A software RSA key and self-signed certificate, made by the JDK's keytool. */
    private fun softwareKey(): SigningKey {
        val store = File(directory, "fake.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "fake", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
            "-dname", "CN=fake", "-keystore", store.path, "-storetype", "PKCS12", "-storepass", PASSWORD,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        val keyStore = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, PASSWORD.toCharArray()) } }
        val certificate = assertNotNull(keyStore.getCertificate("fake")) as X509Certificate
        return SigningKey(keyStore.getKey("fake", PASSWORD.toCharArray()) as PrivateKey, certificate)
    }

    private companion object {
        const val PACKAGE = "app.whittle.generated.notes"
        const val PASSWORD = "changeit"
    }
}
