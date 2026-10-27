package app.whittle.agent

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.flatMap
import app.whittle.core.model.ProviderId
import app.whittle.core.model.Message
import app.whittle.llm.api.CredentialsProvider
import app.whittle.llm.api.ModelAdapterRegistry
import app.whittle.llm.api.ModelDescriptor
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ProviderCatalog
import app.whittle.llm.api.ReasoningEffort

/** A single model call outside an agent run: no tools, low reasoning effort, text back. */
class OneShot(
    private val adapters: ModelAdapterRegistry,
    private val providers: ProviderCatalog,
    private val credentials: CredentialsProvider,
) {
    /** Whether a call can reach [provider] with an API key; not one outside the catalog, such as Claude Code's. */
    fun reaches(provider: ProviderId): Boolean = providers.find(provider) != null

    suspend fun complete(
        model: ModelDescriptor,
        systemPrompt: String,
        userText: String,
        maxOutputTokens: Int,
    ): Outcome<String> = connectModel(adapters, providers, credentials, model).flatMap { connection ->
        val request = ModelRequest(
            model = model,
            systemPrompt = systemPrompt,
            messages = listOf(Message.userText(userText)),
            tools = emptyList(),
            maxOutputTokens = maxOutputTokens,
            reasoningEffort = ReasoningEffort.LOW,
        )
        val text = StringBuilder()
        var failure: HarnessError? = null
        connection.adapter.stream(request, connection.provider, connection.apiKey).collect { event ->
            when (event) {
                is ModelEvent.TextDelta -> text.append(event.text)
                is ModelEvent.Failed -> if (failure == null) failure = event.error
                else -> Unit
            }
        }
        failure?.let { Outcome.Failure(it) } ?: Outcome.Success(text.toString())
    }
}
