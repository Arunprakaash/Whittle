package app.whittle.apps

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.whittle.core.common.Outcome
import com.android.apksig.ApkVerifier
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Signing with a real Android Keystore key, which the JVM tests can't reach: the app's target SDK must still allow it. */
@RunWith(AndroidJUnit4::class)
class KeystoreSigningTest {
    private val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
    private val packageName = "app.whittle.generated.keystoretest"

    @After
    fun removeKey() {
        File(directory, UNSIGNED).delete()
        File(directory, SIGNED).delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("whittle-app:$packageName")
    }

    @Test
    fun signsWithV2AndTheApkVerifies() {
        val unsigned = File(directory, UNSIGNED)
        val signed = File(directory, SIGNED)
        ZipOutputStream(unsigned.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.closeEntry()
        }
        val signer = AppSigner(AndroidKeystoreKeys())

        assertIs<Outcome.Success<Unit>>(signer.sign(unsigned, signed, packageName))

        val result = ApkVerifier.Builder(signed)
            .setMinCheckedPlatformVersion(26)
            .setMaxCheckedPlatformVersion(36)
            .build()
            .verify()
        assertTrue(result.isVerified, result.errors.joinToString())
        assertTrue(result.isVerifiedUsingV2Scheme)
        assertEquals(listOf(signer.existingCertificate(packageName)), result.signerCertificates)
    }

    private companion object {
        const val UNSIGNED = "keystore-unsigned.apk"
        const val SIGNED = "keystore-signed.apk"
    }
}
