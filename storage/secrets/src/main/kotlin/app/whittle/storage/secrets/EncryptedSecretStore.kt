package app.whittle.storage.secrets

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.whittle.core.common.ContentCipher
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.map
import app.whittle.core.config.SecretStoreConfig
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * Secrets encrypted with [cipher] and stored in DataStore. Create exactly one instance per process:
 * DataStore forbids two instances on the same file. The file must be excluded from device backup,
 * because the Keystore key that decrypts it is never backed up.
 */
class EncryptedSecretStore(
    context: Context,
    config: SecretStoreConfig,
    private val cipher: ContentCipher,
) : SecretStore {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        produceFile = { context.applicationContext.preferencesDataStoreFile(config.dataStoreName) },
    )

    override suspend fun put(key: SecretKey, value: Secret): Outcome<Unit> =
        when (val encrypted = cipher.encrypt(value.reveal().toByteArray(Charsets.UTF_8), key.associatedData())) {
            is Outcome.Failure -> encrypted
            is Outcome.Success -> storageCall {
                dataStore.edit { it[key.preferenceKey()] = Base64.encodeToString(encrypted.value, Base64.NO_WRAP) }
            }
        }

    override suspend fun get(key: SecretKey): Outcome<Secret?> {
        val stored = when (val read = storageCall { dataStore.data.first()[key.preferenceKey()] }) {
            is Outcome.Failure -> return read
            is Outcome.Success -> read.value ?: return Outcome.Success(null)
        }
        return cipher.decrypt(Base64.decode(stored, Base64.NO_WRAP), key.associatedData())
            .map { Secret(it.toString(Charsets.UTF_8)) }
    }

    override suspend fun delete(key: SecretKey): Outcome<Unit> =
        storageCall { dataStore.edit { it.remove(key.preferenceKey()) } }

    private suspend fun <T> storageCall(block: suspend () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (exception: IOException) {
        Outcome.Failure(HarnessError.Storage("Secret store unavailable: ${exception.javaClass.simpleName}"))
    }

    private fun SecretKey.preferenceKey(): Preferences.Key<String> = stringPreferencesKey(name)

    private fun SecretKey.associatedData(): ByteArray = name.toByteArray(Charsets.UTF_8)
}
