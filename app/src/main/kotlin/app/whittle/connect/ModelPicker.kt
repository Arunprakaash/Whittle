package app.whittle.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import app.whittle.AppGraph
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.Secret
import app.whittle.core.common.flatMap
import app.whittle.core.common.getOrNull
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
    /** Changing the model of a provider already connected: its saved key is used, nothing is asked. */
    val switching: Boolean = false,
) {
    val provider: ProviderDescriptor get() = providers.first { it.id == providerId }
}

sealed interface ConnectPhase {
    data class EnterKey(val error: ConnectError?) : ConnectPhase

    data object Checking : ConnectPhase

    /** The saved key's models couldn't be listed; the key itself is kept. */
    data class LoadFailed(val error: ConnectError) : ConnectPhase

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
 *
 * Given a [provider] already connected (the current model's, by default), it changes the model instead: the
 * provider's saved key lists its models and is never asked for again. Only a key that's missing is asked for.
 * A provider outside the catalog (Claude Code) is treated as none.
 */
class ModelPicker(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    current: ModelDescriptor? = null,
    provider: ProviderId? = current?.providerId,
) : ModelPickerHost {
    private val connected: ProviderId? = provider?.takeIf { id -> graph.providers.providers.any { it.id == id } }

    private val _state = MutableStateFlow(
        ConnectState(
            providers = graph.providers.providers,
            providerId = connected ?: graph.providers.providers.first().id,
            phase = if (connected != null) ConnectPhase.Checking else ConnectPhase.EnterKey(error = null),
            switching = connected != null,
        ),
    )
    /** The phone's own view of the picker, with its models as the catalog describes them. */
    val connectState: StateFlow<ConnectState> = _state.asStateFlow()

    override val state: StateFlow<PickerState> = _state.map(::picker).stateIn(scope, SharingStarted.Eagerly, picker(_state.value))

    override fun act(action: PickerAction) {
        when (action) {
            is PickerAction.SelectProvider -> selectProvider(ProviderId(action.id))
            is PickerAction.Connect -> {
                updateKey(action.key)
                connect()
            }
            is PickerAction.Query -> updateQuery(action.query)
            PickerAction.BackToKey -> backToKey()
            PickerAction.Retry -> retry()
            is PickerAction.Choose -> (_state.value.phase as? ConnectPhase.ChooseModel)?.models?.firstOrNull { it.id.value == action.modelId }?.let(::choose)
        }
    }

    init {
        retry()
    }

    /** Lists the current provider's models again with its saved key. */
    fun retry() {
        val provider = connected ?: return
        _state.update { it.copy(phase = ConnectPhase.Checking) }
        scope.launch {
            val key = graph.secrets.get(SecretKey.providerApiKey(provider)).getOrNull()
            val phase = if (key == null) {
                ConnectPhase.EnterKey(error = null)
            } else {
                when (val listed = graph.modelLister.listModels(_state.value.provider, key)) {
                    is Outcome.Success -> ConnectPhase.ChooseModel(usable(listed.value))
                    is Outcome.Failure -> ConnectPhase.LoadFailed(listed.error.toConnectError())
                }
            }
            _state.update { it.copy(phase = phase) }
        }
    }

    /** Picks the provider; one whose key is still saved on this phone lists its models without asking for it again. */
    fun selectProvider(id: ProviderId) {
        _state.update { it.copy(providerId = id, phase = ConnectPhase.EnterKey(error = null)) }
        scope.launch {
            val key = graph.secrets.get(SecretKey.providerApiKey(id)).getOrNull() ?: return@launch
            if (_state.value.providerId != id) return@launch
            _state.update { it.copy(phase = ConnectPhase.Checking) }
            val phase = when (val listed = graph.modelLister.listModels(_state.value.provider, key)) {
                is Outcome.Success -> ConnectPhase.ChooseModel(usable(listed.value))
                is Outcome.Failure -> ConnectPhase.EnterKey(listed.error.toConnectError())
            }
            _state.update { if (it.providerId == id) it.copy(phase = phase) else it }
        }
    }

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
        scope.launch {
            val phase = graph.keyVerifier.verify(current.provider, key)
                .flatMap { graph.modelLister.listModels(current.provider, key) }
                .flatMap { models -> graph.secrets.put(SecretKey.providerApiKey(current.providerId), key).map { models } }
                .let { outcome ->
                    when (outcome) {
                        is Outcome.Failure -> ConnectPhase.EnterKey(outcome.error.toConnectError())
                        is Outcome.Success -> ConnectPhase.ChooseModel(usable(outcome.value))
                    }
                }
            _state.update { it.copy(phase = phase) }
        }
    }

    fun choose(model: ModelDescriptor, then: () -> Unit = {}) {
        scope.launch {
            graph.connections.save(model)
            then()
        }
    }

    private fun picker(state: ConnectState) = PickerState(
        providers = state.providers.map { ProviderEntry(it.id.value, it.displayName, it.tagline, it.keysUrl) },
        providerId = state.providerId.value,
        phase = when (val phase = state.phase) {
            is ConnectPhase.EnterKey -> PickerPhase.EnterKey(phase.error?.let(::pickerError))
            ConnectPhase.Checking -> PickerPhase.Checking
            is ConnectPhase.LoadFailed -> PickerPhase.LoadFailed(pickerError(phase.error))
            is ConnectPhase.ChooseModel -> PickerPhase.ChooseModel(phase.models.map { ModelEntry(it.id.value, it.displayName) }, phase.query)
        },
        switching = state.switching,
    )

    private fun pickerError(error: ConnectError): PickerError = when (error) {
        ConnectError.KeyRejected -> PickerError.KeyRejected
        ConnectError.Unreachable -> PickerError.Unreachable
        is ConnectError.ProviderFailed -> PickerError.ProviderFailed(error.statusCode)
        ConnectError.StorageFailed -> PickerError.StorageFailed
        ConnectError.Unknown -> PickerError.Unknown
    }

    /** Models that can use tools, newest families first (gpt-6 before gpt-3.5); ids sort that way within a provider. */
    private fun usable(models: List<ModelDescriptor>): List<ModelDescriptor> =
        models.filter { it.capabilities.toolCalling }.sortedByDescending { it.id.value }

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

/** The picker on the phone's own screen, kept across rotations like any screen's state. */
class ConnectViewModel(graph: AppGraph, current: ModelDescriptor? = null, provider: ProviderId? = current?.providerId) : ViewModel() {
    val picker = ModelPicker(graph, viewModelScope, current, provider)
}

private fun String.searchable(): String = lowercase().filter { it.isLetterOrDigit() }
