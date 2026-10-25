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
import com.anthropic.models.ErrorType
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
            val status = statusOf(exception)
            HarnessError.Provider(status, exception.message ?: "Claude answered HTTP $status")
        } catch (exception: AnthropicIoException) {
            HarnessError.Network(exception.message ?: exception.javaClass.simpleName)
        } catch (exception: AnthropicException) {
            HarnessError.Serialization(exception.message ?: exception.javaClass.simpleName)
        }
        failure?.let { emit(ModelEvent.Failed(it)) }
    }.flowOn(Dispatchers.IO)

    /**
     * The HTTP status the failure stands for. An error sent inside the stream (Claude overloaded mid-answer, say) arrives
     * on a response that began with 200, so its type gives the status it would have had, and the runner can retry it.
     */
    private fun statusOf(exception: AnthropicServiceException): Int {
        val status = exception.statusCode()
        if (status !in HTTP_OK) return status
        return exception.errorType().orElse(null)?.let(STATUS_BY_ERROR_TYPE::get) ?: status
    }

    @Synchronized
    private fun clientFor(provider: ProviderDescriptor, apiKey: Secret): AnthropicClient {
        val identity = provider.baseUrl + "\n" + apiKey.reveal()
        clients[provider.id.value]?.takeIf { it.first == identity }?.let { return it.second }
        return newClient(provider, apiKey).also { clients[provider.id.value] = identity to it }
    }

    private companion object {
        val HTTP_OK = 200..299

        /** The status Anthropic's API gives each error type (docs: Errors, HTTP errors). */
        val STATUS_BY_ERROR_TYPE: Map<ErrorType, Int> = mapOf(
            ErrorType.INVALID_REQUEST_ERROR to 400,
            ErrorType.AUTHENTICATION_ERROR to 401,
            ErrorType.BILLING_ERROR to 402,
            ErrorType.PERMISSION_ERROR to 403,
            ErrorType.NOT_FOUND_ERROR to 404,
            ErrorType.RATE_LIMIT_ERROR to 429,
            ErrorType.API_ERROR to 500,
            ErrorType.TIMEOUT_ERROR to 504,
            ErrorType.OVERLOADED_ERROR to 529,
        )

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
