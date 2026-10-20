package app.whittle.export

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.android.apksig.ApkSigner
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.ZonedDateTime
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Signs exported apps with one key that never leaves this phone's secure hardware (Android Keystore), created
 * the first time. Every app from this phone shares it, so exporting an app again installs as an update.
 */
class AppSigner(private val keyAlias: String, private val certificateSubject: String, private val validityYears: Long, private val minSdk: Int) {
    fun sign(unsigned: File, signed: File) {
        val (key, certificate) = signingKey()
        val signer = ApkSigner.SignerConfig.Builder(SIGNER_NAME, key, listOf(certificate)).build()
        ApkSigner.Builder(listOf(signer))
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setMinSdkVersion(minSdk)
            .setV1SigningEnabled(false)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setCreatedBy(CREATED_BY)
            .build()
            .sign()
    }

    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!store.containsAlias(keyAlias)) {
            val now = ZonedDateTime.now()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE).apply {
                initialize(
                    KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_SIGN)
                        .setKeySize(KEY_BITS)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                        .setCertificateSubject(X500Principal(certificateSubject))
                        .setCertificateSerialNumber(BigInteger.ONE)
                        .setCertificateNotBefore(Date.from(now.toInstant()))
                        .setCertificateNotAfter(Date.from(now.plusYears(validityYears).toInstant()))
                        .build(),
                )
            }.generateKeyPair()
        }
        return store.getKey(keyAlias, null) as PrivateKey to store.getCertificate(keyAlias) as X509Certificate
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_BITS = 2048
        const val SIGNER_NAME = "WHITTLE"
        const val CREATED_BY = "Whittle"
    }
}
