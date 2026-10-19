package com.mobileagent.llm.http

import com.mobileagent.core.common.HarnessError
import com.mobileagent.llm.api.ModelEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Turns one provider's stream events into canonical [ModelEvent]s. A new instance is used per request. */
interface StreamEventParser {
    fun onData(data: String): List<ModelEvent>

    /** Called once the stream ends; emits whatever is still pending, ending with Completed. */
    fun finish(): List<ModelEvent>
}

/**
 * Runs a streaming HTTP request and feeds its Server-Sent Events through a [StreamEventParser].
 * HTTP and network failures become [ModelEvent.Failed]; cancelling the flow cancels the call.
 */
class SseModelStream(
    private val httpClient: OkHttpClient,
    private val maxErrorBodyChars: Int = DEFAULT_MAX_ERROR_BODY_CHARS,
) {
    fun stream(request: Request, parser: StreamEventParser): Flow<ModelEvent> = flow {
        val call = httpClient.newCall(request.newBuilder().header(ACCEPT_HEADER, EVENT_STREAM_MEDIA_TYPE).build())
        currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    emit(ModelEvent.Failed(HarnessError.Provider(response.code, response.errorSummary())))
                    return@use
                }
                val source = response.body?.source()
                    ?: return@use emit(ModelEvent.Failed(HarnessError.Network("Empty response body")))
                val decoder = ServerSentEventDecoder()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    decoder.feed(line)?.let { data -> parser.onData(data).forEach { emit(it) } }
                }
                decoder.flush()?.let { data -> parser.onData(data).forEach { emit(it) } }
                parser.finish().forEach { emit(it) }
            }
        } catch (exception: IOException) {
            emit(ModelEvent.Failed(HarnessError.Network(exception.message ?: exception.javaClass.simpleName)))
        }
    }.flowOn(Dispatchers.IO)

    private fun Response.errorSummary(): String =
        (body?.string().orEmpty()).take(maxErrorBodyChars).ifBlank { message }

    private companion object {
        const val DEFAULT_MAX_ERROR_BODY_CHARS = 2_000
        const val ACCEPT_HEADER = "Accept"
        const val EVENT_STREAM_MEDIA_TYPE = "text/event-stream"
    }
}
