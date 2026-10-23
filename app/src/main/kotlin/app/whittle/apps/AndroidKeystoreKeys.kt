package app.whittle.apps

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/** One RSA key per generated app in the Android Keystore, created on first use. The key never leaves the device's secure hardware. */
class AndroidKeystoreKeys : SigningKeys {
    override fun keyFor(packageName: String): Outcome<SigningKey> = try {
        val alias = ALIAS_PREFIX + packageName
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!store.containsAlias(alias)) generate(alias, packageName)
        val privateKey = store.getKey(alias, null) as? PrivateKey
        val certificate = store.getCertificate(alias) as? X509Certificate
        if (privateKey == null || certificate == null) {
            Outcome.Failure(HarnessError.Storage("The signing key for $packageName is missing from the Keystore."))
        } else {
            Outcome.Success(SigningKey(privateKey, certificate))
        }
    } catch (exception: GeneralSecurityException) {
        Outcome.Failure(HarnessError.Authentication("Couldn't get the signing key for $packageName: ${exception.message}"))
    } catch (exception: IOException) {
        Outcome.Failure(HarnessError.Storage("Couldn't open the Keystore for $packageName: ${exception.message}"))
    } catch (exception: ProviderException) {
        Outcome.Failure(HarnessError.Authentication("The Keystore refused a signing key for $packageName: ${exception.message}"))
    }

    override fun existingCertificate(packageName: String): X509Certificate? = try {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val alias = ALIAS_PREFIX + packageName
        if (store.containsAlias(alias)) store.getCertificate(alias) as? X509Certificate else null
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun generate(alias: String, packageName: String) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setKeySize(KEY_BITS)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=$packageName"))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS_PREFIX = "whittle-app:"
        const val KEY_BITS = 3072
    }
}
