package app.whittle.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.whittle.AppGraph
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.flatMap
import app.whittle.core.common.map
import app.whittle.core.model.ProviderId
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.storage.secrets.SecretKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectState(
    val providers: List<ProviderDescriptor>,
    val providerId: ProviderId,
    val key: String = "",
    val phase: ConnectPhase = ConnectPhase.EnterKey(error = null),
) {
    val provider: ProviderDescriptor get() = providers.first { it.id == providerId }
}

sealed interface ConnectPhase {
    data class EnterKey(val error: ConnectError?) : ConnectPhase

    data object Checking : ConnectPhase

    data class ChooseModel(val models: List<ModelDescriptor>, val query: String = "") : ConnectPhase {
        /** Matches ignoring case, spaces and punctuation, so "gpt5" finds "gpt-5.6-terra". */
        val visible: List<ModelDescriptor>
            get() {
                val needle = query.searchable()
                if (needle.isEmpty()) return models
                return models.filter { needle in it.displayName.searchable() || needle in it.id.value.searchable() }
            }
    }
}

/** Why connecting failed, in terms the screen turns into plain words. */
sealed interface ConnectError {
    data object KeyRejected : ConnectError

    data object Unreachable : ConnectError

    data class ProviderFailed(val statusCode: Int) : ConnectError

    data object StorageFailed : ConnectError

    data object Unknown : ConnectError
}

/**
 * Connects a provider: the key is checked against the provider, the models are listed, and only then
 * is the key stored (encrypted, on this phone). Picking a model finishes the connection.
 */
class ConnectViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow(
        ConnectState(providers = graph.providers.providers, providerId = graph.providers.providers.first().id),
    )
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    fun selectProvider(id: ProviderId) = _state.update { it.copy(providerId = id, phase = ConnectPhase.EnterKey(error = null)) }

    fun updateKey(key: String) = _state.update { it.copy(key = key, phase = ConnectPhase.EnterKey(error = null)) }

    fun updateQuery(query: String) = _state.update { state ->
        val phase = state.phase as? ConnectPhase.ChooseModel ?: return@update state
        state.copy(phase = phase.copy(query = query))
    }

    fun backToKey() = _state.update { it.copy(phase = ConnectPhase.EnterKey(error = null)) }

    fun connect() {
        val current = _state.value
        val key = Secret(current.key.trim())
        if (key.reveal().isEmpty() || current.phase == ConnectPhase.Checking) return
        _state.update { it.copy(phase = ConnectPhase.Checking) }
        viewModelScope.launch {
            val phase = graph.keyVerifier.verify(current.provider, key)
                .flatMap { graph.modelLister.listModels(current.provider, key) }
                .flatMap { models -> graph.secrets.put(SecretKey.providerApiKey(current.providerId), key).map { models } }
                .let { outcome ->
                    when (outcome) {
                        is Outcome.Failure -> ConnectPhase.EnterKey(outcome.error.toConnectError())
                        is Outcome.Success -> ConnectPhase.ChooseModel(
                            outcome.value.filter { it.capabilities.toolCalling }.sortedBy { it.displayName.lowercase() },
                        )
                    }
                }
            _state.update { it.copy(phase = phase) }
        }
    }

    fun choose(model: ModelDescriptor) {
        viewModelScope.launch { graph.connections.save(model) }
    }

    private fun HarnessError.toConnectError(): ConnectError = when (this) {
        is HarnessError.Authentication -> ConnectError.KeyRejected
        is HarnessError.Provider -> if (statusCode in KEY_REJECTED_CODES) ConnectError.KeyRejected else ConnectError.ProviderFailed(statusCode)
        is HarnessError.Network -> ConnectError.Unreachable
        is HarnessError.Storage -> ConnectError.StorageFailed
        else -> ConnectError.Unknown
    }

    private companion object {
        val KEY_REJECTED_CODES = setOf(401, 403)
    }
}

private fun String.searchable(): String = lowercase().filter { it.isLetterOrDigit() }
