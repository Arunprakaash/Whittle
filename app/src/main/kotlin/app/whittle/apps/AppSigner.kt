package app.whittle.apps

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
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
    /**
     * Signs [unsigned] into [signed] for Androids from [minSdk] up: with the v2 scheme, which Android 7 and later check,
     * and the v1 (JAR) scheme too below that, which Android 6 and earlier need.
     */
    fun sign(unsigned: File, signed: File, packageName: String, minSdk: Int): Outcome<Unit> {
        val key = when (val found = keys.keyFor(packageName)) {
            is Outcome.Failure -> return found
            is Outcome.Success -> found.value
        }
        return try {
            val config = ApkSigner.SignerConfig.Builder(SIGNER_NAME, KeyConfig.Jca(key.privateKey), listOf(key.certificate)).build()
            ApkSigner.Builder(listOf(config))
                .setInputApk(unsigned)
                .setOutputApk(signed)
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(minSdk < V2_SDK)
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

    /** The certificate of the key already held for [packageName], or null; checking a crash or an install must not make keys. */
    fun existingCertificate(packageName: String): X509Certificate? = keys.existingCertificate(packageName)

    private companion object {
        const val SIGNER_NAME = "whittle"
        /** The first Android that checks v2 signatures; older ones check only the JAR signature. */
        const val V2_SDK = 24
    }
}
