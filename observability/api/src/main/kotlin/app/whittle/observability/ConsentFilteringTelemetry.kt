package app.whittle.observability

import app.whittle.core.config.ConsentLevel

/**
 * Applies the user's consent before anything reaches an exporter: OFF drops everything, METADATA
 * strips content attributes, FULL_CONTENT keeps content but truncates long values.
 */
class ConsentFilteringTelemetry(
    private val delegate: Telemetry,
    private val consent: () -> ConsentLevel,
    private val maxAttributeChars: Int,
    private val fallback: Telemetry,
) : Telemetry {
    override fun event(event: TelemetryEvent, attributes: Attributes) {
        active()?.event(event, filter(attributes))
    }

    override fun startSpan(name: SpanName, parent: SpanContext?, attributes: Attributes): Span {
        val target = active() ?: fallback
        val span = target.startSpan(name, parent, filter(attributes))
        return object : Span {
            override val context: SpanContext = span.context

            override fun end(status: SpanStatus, attributes: Attributes) = span.end(status, filter(attributes))
        }
    }

    override fun generation(record: GenerationRecord) {
        active()?.generation(record)
    }

    private fun active(): Telemetry? = delegate.takeUnless { consent() == ConsentLevel.OFF }

    private fun filter(attributes: Attributes): Attributes = when (consent()) {
        ConsentLevel.OFF -> Attributes.Empty
        ConsentLevel.METADATA -> attributes.filter { it.sensitivity == Sensitivity.METADATA }.truncated()
        ConsentLevel.FULL_CONTENT -> attributes.truncated()
    }

    private fun Attributes.truncated(): Attributes = mapValues { value ->
        if (value is AttributeValue.Text && value.value.length > maxAttributeChars) {
            value.copy(value = value.value.take(maxAttributeChars))
        } else {
            value
        }
    }
}
