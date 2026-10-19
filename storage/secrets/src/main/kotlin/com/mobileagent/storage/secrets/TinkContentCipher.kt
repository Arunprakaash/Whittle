package com.mobileagent.storage.secrets

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.mobileagent.core.common.ContentCipher
import com.mobileagent.core.common.HarnessError
import com.mobileagent.core.common.Outcome
import com.mobileagent.core.config.SecretStoreConfig
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Tink AEAD whose keyset is wrapped by an Android Keystore key. The Keystore key never leaves secure
 * hardware, so ciphertexts can't be decrypted off the device.
 */
class TinkContentCipher(context: Context, config: SecretStoreConfig) : ContentCipher {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context.applicationContext, config.keysetName, config.keysetPreferencesFile)
            .withKeyTemplate(KeyTemplates.get(config.keyTemplate))
            .withMasterKeyUri(config.masterKeyUri)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): Outcome<ByteArray> =
        guarded { aead.encrypt(plaintext, associatedData) }

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): Outcome<ByteArray> =
        guarded { aead.decrypt(ciphertext, associatedData) }

    private inline fun guarded(block: () -> ByteArray): Outcome<ByteArray> = try {
        Outcome.Success(block())
    } catch (exception: GeneralSecurityException) {
        Outcome.Failure(HarnessError.Storage("Encryption failure: ${exception.javaClass.simpleName}"))
    } catch (exception: IOException) {
        Outcome.Failure(HarnessError.Storage("Keyset unavailable: ${exception.javaClass.simpleName}"))
    }
}
