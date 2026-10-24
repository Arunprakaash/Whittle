package app.whittle.llm.anthropic

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Secret
import app.whittle.llm.api.ApiFormat
import app.whittle.llm.api.ModelAdapter
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ProviderDescriptor
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.RawMessageStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.time.Duration

/**
 * Streams from Anthropic's Messages API through Anthropic's own Java SDK. The SDK doesn't retry: the agent loop already
 * retries a failed step, and two layers of retries would multiply the wait. Cancelling the flow closes the stream.
 */
class AnthropicMessagesAdapter(
    private val newClient: (ProviderDescriptor, Secret) -> AnthropicClient = ::client,
) : ModelAdapter {
    override val apiFormat: ApiFormat = ApiFormat.ANTHROPIC_MESSAGES

    private val mapper = AnthropicRequestMapper()

    /** One client per provider, kept while its address and key stay the same; a new key gets a new client. */
    private val clients = mutableMapOf<String, Pair<String, AnthropicClient>>()

    override fun stream(request: ModelRequest, provider: ProviderDescriptor, apiKey: Secret): Flow<ModelEvent> = flow {
        val parser = AnthropicStreamParser()
        var open: StreamResponse<RawMessageStreamEvent>? = null
        currentCoroutineContext()[Job]?.invokeOnCompletion { open?.close() }
        val failure = try {
            clientFor(provider, apiKey).messages().createStreaming(mapper.map(request, provider)).use { response ->
                open = response
                val events = response.stream().iterator()
                while (events.hasNext()) parser.onEvent(events.next()).forEach { emit(it) }
            }
            parser.finish().forEach { emit(it) }
            null
        } catch (exception: AnthropicServiceException) {
            HarnessError.Provider(exception.statusCode(), exception.message ?: "Claude answered HTTP ${exception.statusCode()}")
        } catch (exception: AnthropicIoException) {
            HarnessError.Network(exception.message ?: exception.javaClass.simpleName)
        } catch (exception: AnthropicException) {
            HarnessError.Serialization(exception.message ?: exception.javaClass.simpleName)
        }
        failure?.let { emit(ModelEvent.Failed(it)) }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    private fun clientFor(provider: ProviderDescriptor, apiKey: Secret): AnthropicClient {
        val identity = provider.baseUrl + "\n" + apiKey.reveal()
        clients[provider.id.value]?.takeIf { it.first == identity }?.let { return it.second }
        return newClient(provider, apiKey).also { clients[provider.id.value] = identity to it }
    }

    private companion object {
        /** Long answers with thinking can take minutes; the stream keeps the connection busy meanwhile. */
        val TIMEOUT: Duration = Duration.ofMinutes(10)

        fun client(provider: ProviderDescriptor, apiKey: Secret): AnthropicClient = AnthropicOkHttpClient.builder()
            .apiKey(apiKey.reveal())
            .baseUrl(provider.baseUrl.trimEnd('/'))
            .maxRetries(0)
            .timeout(TIMEOUT)
            .build()
    }
}
