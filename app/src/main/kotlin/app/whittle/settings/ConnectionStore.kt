package app.whittle.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import app.whittle.llm.api.ModelDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Which model the user connected. The key itself lives in the encrypted secret store, never here. */
class ConnectionStore(context: Context, name: String) {
    private val dataStore = PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(name) }

    val model: Flow<ModelDescriptor?> = dataStore.data.map { preferences ->
        preferences[MODEL]?.let { stored ->
            try {
                Json.decodeFromString(ModelDescriptor.serializer(), stored)
            } catch (exception: SerializationException) {
                null // Written by an older app version; the user simply connects again.
            }
        }
    }

    suspend fun save(model: ModelDescriptor) {
        dataStore.edit { it[MODEL] = Json.encodeToString(ModelDescriptor.serializer(), model) }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(MODEL) }
    }

    private companion object {
        val MODEL = stringPreferencesKey("model")
    }
}
