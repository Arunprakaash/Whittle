package app.whittle.llm.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerSentEventDecoderTest {
    private val decoder = ServerSentEventDecoder()

    @Test
    fun `joins multi-line data and ignores comments`() {
        assertNull(decoder.feed(": keep-alive"))
        assertNull(decoder.feed("data: one"))
        assertNull(decoder.feed("data:two"))
        assertEquals("one\ntwo", decoder.feed(""))
    }

    @Test
    fun `flush returns a trailing event`() {
        decoder.feed("data: last")
        assertEquals("last", decoder.flush())
        assertNull(decoder.flush())
    }
}
