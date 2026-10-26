package app.whittle.connect

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Bringing a model: a provider, its key, then one of its models. The phone checks and keeps keys, encrypted; a computer
 * asks the phone through its link, and a key typed there goes straight to the phone and never comes back.
 */
interface ModelPickerHost {
    val state: StateFlow<PickerState>

    fun act(action: PickerAction)
}

@Serializable
data class PickerState(
    val providers: List<ProviderEntry> = emptyList(),
    val providerId: String = "",
    val phase: PickerPhase = PickerPhase.Checking,
    /** Changing the model of a provider already connected: its saved key is used, nothing is asked. */
    val switching: Boolean = false,
) {
    val provider: ProviderEntry? get() = providers.firstOrNull { it.id == providerId }
}

/** A provider to bring a model from, as the user picks it. */
@Serializable
data class ProviderEntry(val id: String, val displayName: String, val tagline: String? = null, val keysUrl: String? = null)

@Serializable
data class ModelEntry(val id: String, val displayName: String)

@Serializable
sealed interface PickerPhase {
    @Serializable
    @SerialName("enter_key")
    data class EnterKey(val error: PickerError? = null) : PickerPhase

    @Serializable
    @SerialName("checking")
    data object Checking : PickerPhase

    /** The saved key's models couldn't be listed; the key itself is kept. */
    @Serializable
    @SerialName("load_failed")
    data class LoadFailed(val error: PickerError) : PickerPhase

    @Serializable
    @SerialName("choose_model")
    data class ChooseModel(val models: List<ModelEntry>, val query: String = "") : PickerPhase {
        /** Matches ignoring case, spaces and punctuation, so "gpt5" finds "gpt-5.6-terra". */
        val visible: List<ModelEntry>
            get() {
                val needle = query.searchable()
                if (needle.isEmpty()) return models
                return models.filter { needle in it.displayName.searchable() || needle in it.id.searchable() }
            }
    }
}

/** Why connecting failed, in terms the screen turns into plain words. */
@Serializable
sealed interface PickerError {
    @Serializable
    @SerialName("key_rejected")
    data object KeyRejected : PickerError

    @Serializable
    @SerialName("unreachable")
    data object Unreachable : PickerError

    @Serializable
    @SerialName("provider_failed")
    data class ProviderFailed(val statusCode: Int) : PickerError

    @Serializable
    @SerialName("storage_failed")
    data object StorageFailed : PickerError

    @Serializable
    @SerialName("unknown")
    data object Unknown : PickerError
}

@Serializable
sealed interface PickerAction {
    /** Picks the provider; one whose key is still saved lists its models without asking for it again. */
    @Serializable
    @SerialName("provider")
    data class SelectProvider(val id: String) : PickerAction

    /** Checks [key] with the provider, lists its models, and only then keeps it. */
    @Serializable
    @SerialName("connect")
    data class Connect(val key: String) : PickerAction

    @Serializable
    @SerialName("query")
    data class Query(val query: String) : PickerAction

    @Serializable
    @SerialName("back_to_key")
    data object BackToKey : PickerAction

    /** Lists the saved key's models again. */
    @Serializable
    @SerialName("retry")
    data object Retry : PickerAction

    @Serializable
    @SerialName("choose")
    data class Choose(val modelId: String) : PickerAction
}

private fun String.searchable(): String = lowercase().filter { it.isLetterOrDigit() }
