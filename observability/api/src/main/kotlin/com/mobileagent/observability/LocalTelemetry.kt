package com.mobileagent.observability

import com.mobileagent.core.common.IdGenerator

/** Creates span ids without exporting anything. Used when telemetry is off and in tests. */
class LocalTelemetry(private val idGenerator: IdGenerator) : Telemetry {
    override fun event(event: TelemetryEvent, attributes: Attributes) = Unit

    override fun startSpan(name: SpanName, parent: SpanContext?, attributes: Attributes): Span {
        val context = SpanContext(
            traceId = parent?.traceId ?: TraceId(idGenerator.newId()),
            spanId = SpanId(idGenerator.newId()),
        )
        return object : Span {
            override val context: SpanContext = context

            override fun end(status: SpanStatus, attributes: Attributes) = Unit
        }
    }

    override fun generation(record: GenerationRecord) = Unit
}
