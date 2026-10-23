package app.whittle.apps

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.getOrNull
import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import com.android.apksig.apk.ApkFormatException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.cert.X509Certificate

/** A private key and the certificate that vouches for it. */
data class SigningKey(val privateKey: PrivateKey, val certificate: X509Certificate)

/** Where the signing key for each generated app comes from. */
interface SigningKeys {
    /** The key for [packageName], made on first use. */
    fun keyFor(packageName: String): Outcome<SigningKey>

    /** The certificate of the key already held for [packageName], or null; it never makes a key. */
    fun existingCertificate(packageName: String): X509Certificate?
}

/** Signs generated apps with the key [keys] holds for their package, so an app keeps one identity across updates. */
class AppSigner(private val keys: SigningKeys) {
    /** Signs [unsigned] into [signed] with the v2 scheme, which every supported Android version checks. */
    fun sign(unsigned: File, signed: File, packageName: String): Outcome<Unit> {
        val key = when (val found = keys.keyFor(packageName)) {
            is Outcome.Failure -> return found
            is Outcome.Success -> found.value
        }
        return try {
            val config = ApkSigner.SignerConfig.Builder(SIGNER_NAME, KeyConfig.Jca(key.privateKey), listOf(key.certificate)).build()
            ApkSigner.Builder(listOf(config))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(MIN_SDK)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .build()
                .sign()
            Outcome.Success(Unit)
        } catch (exception: IOException) {
            signed.delete()
            Outcome.Failure(HarnessError.Storage("Couldn't sign $packageName: ${exception.message}"))
        } catch (exception: GeneralSecurityException) {
            signed.delete()
            Outcome.Failure(HarnessError.Authentication("Couldn't sign $packageName: ${exception.message}"))
        } catch (exception: ApkFormatException) {
            signed.delete()
            Outcome.Failure(HarnessError.Storage("Couldn't sign $packageName, its APK is malformed: ${exception.message}"))
        } catch (exception: RuntimeException) {
            signed.delete()
            Outcome.Failure(HarnessError.Storage("Couldn't sign $packageName: ${exception.message}"))
        }
    }

    /** The certificate [packageName] is signed with, or null when its key isn't available. */
    fun certificateFor(packageName: String): X509Certificate? = keys.keyFor(packageName).getOrNull()?.certificate

    /** Like [certificateFor], but only for a key that already exists; checking a crash must not make keys. */
    fun existingCertificate(packageName: String): X509Certificate? = keys.existingCertificate(packageName)

    private companion object {
        const val SIGNER_NAME = "whittle"
        const val MIN_SDK = 26
    }
}
