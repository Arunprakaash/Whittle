package app.whittle.llm.responses

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Secret
import app.whittle.core.model.Message
import app.whittle.llm.api.ModelEvent
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ReasoningEffort
import app.whittle.llm.api.StopReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenAiResponsesAdapterTest {
    private val server = MockWebServer()
    private val adapter = OpenAiResponsesAdapter(OkHttpClient())

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.shutdown()

    @Test
    fun `posts to the responses path and streams text`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "event: response.output_text.delta\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\n" +
                    "event: response.completed\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":3,\"output_tokens\":1}}}\n\n",
            ),
        )

        val events = adapter.stream(request(), responsesProvider(server.url("/v1/").toString()), Secret("sk")).toList()

        assertEquals(ModelEvent.TextDelta("Hello"), events.first())
        assertEquals(ModelEvent.Completed(StopReason.END_TURN), events.last())
        val recorded = server.takeRequest()
        assertEquals("/v1/responses", recorded.path)
        assertEquals("Bearer sk", recorded.getHeader("Authorization"))
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
    }

    @Test
    fun `http error becomes a provider failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        val failed = assertIs<ModelEvent.Failed>(
            adapter.stream(request(), responsesProvider(server.url("/v1/").toString()), Secret("sk")).toList().single(),
        )
        assertEquals(429, assertIs<HarnessError.Provider>(failed.error).statusCode)
    }

    private fun request() = ModelRequest(
        model = reasoningModel(),
        systemPrompt = "System",
        messages = listOf(Message.userText("Hi")),
        tools = emptyList(),
        maxOutputTokens = 64,
        reasoningEffort = ReasoningEffort.LOW,
    )
}
