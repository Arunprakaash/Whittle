package app.whittle.observability

import app.whittle.core.common.IdGenerator
import app.whittle.core.config.ConsentLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsentFilteringTelemetryTest {
    private val recorder = RecordingTelemetry()
    private var consent = ConsentLevel.METADATA
    private val telemetry = ConsentFilteringTelemetry(
        delegate = recorder,
        consent = { consent },
        maxAttributeChars = 5,
        fallback = LocalTelemetry(IdGenerator { "id" }),
    )
    private val attributes = Attributes.of {
        metadata("tool_id", "web.fetch")
        content("prompt", "secret prompt")
    }

    @Test
    fun `metadata consent strips content`() {
        telemetry.event(TelemetryEvent.RUN_STARTED, attributes)
        assertEquals(setOf("tool_id"), recorder.events.single().second.values.keys)
    }

    @Test
    fun `off sends nothing`() {
        consent = ConsentLevel.OFF
        telemetry.event(TelemetryEvent.RUN_STARTED, attributes)
        assertTrue(recorder.events.isEmpty())
    }

    @Test
    fun `full content is truncated`() {
        consent = ConsentLevel.FULL_CONTENT
        telemetry.event(TelemetryEvent.RUN_STARTED, attributes)
        val prompt = recorder.events.single().second.values.getValue("prompt") as AttributeValue.Text
        assertEquals("secre", prompt.value)
    }

    private class RecordingTelemetry : Telemetry {
        val events = mutableListOf<Pair<TelemetryEvent, Attributes>>()
        private val local = LocalTelemetry(IdGenerator { "id" })

        override fun event(event: TelemetryEvent, attributes: Attributes) {
            events += event to attributes
        }

        override fun startSpan(name: SpanName, parent: SpanContext?, attributes: Attributes): Span = local.startSpan(name, parent, attributes)

        override fun generation(record: GenerationRecord) = Unit
    }
}
