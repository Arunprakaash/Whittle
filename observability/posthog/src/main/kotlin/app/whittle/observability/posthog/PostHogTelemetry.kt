package app.whittle.observability.posthog

import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.observability.AttributeValue
import app.whittle.observability.Attributes
import app.whittle.observability.GenerationRecord
import app.whittle.observability.Span
import app.whittle.observability.SpanContext
import app.whittle.observability.SpanId
import app.whittle.observability.SpanName
import app.whittle.observability.SpanStatus
import app.whittle.observability.Telemetry
import app.whittle.observability.TelemetryEvent
import app.whittle.observability.TraceId

/**
 * Maps harness telemetry onto PostHog: events become product events, spans and model calls become
 * PostHog LLM analytics events ($ai_span, $ai_generation) grouped into one trace per run.
 * Wrap it in ConsentFilteringTelemetry; this class exports whatever it receives.
 */
class PostHogTelemetry(
    private val client: PostHogClient,
    private val idGenerator: IdGenerator,
    private val clock: EpochClock,
) : Telemetry {
    override fun event(event: TelemetryEvent, attributes: Attributes) {
        client.capture(event.wireName, attributes.toProperties())
    }

    override fun startSpan(name: SpanName, parent: SpanContext?, attributes: Attributes): Span {
        val context = SpanContext(
            traceId = parent?.traceId ?: TraceId(idGenerator.newId()),
            spanId = SpanId(idGenerator.newId()),
        )
        val startedAt = clock.nowMillis()
        val startProperties = attributes.toProperties()
        return object : Span {
            override val context: SpanContext = context

            override fun end(status: SpanStatus, attributes: Attributes) {
                val properties = buildMap {
                    putAll(startProperties)
                    putAll(attributes.toProperties())
                    put(AiProperty.TRACE_ID, context.traceId.value)
                    put(AiProperty.SPAN_ID, context.spanId.value)
                    put(AiProperty.PARENT_ID, parent?.spanId?.value ?: context.traceId.value)
                    put(AiProperty.SPAN_NAME, name.wireName)
                    put(AiProperty.LATENCY_SECONDS, secondsSince(startedAt))
                    put(AiProperty.IS_ERROR, status == SpanStatus.ERROR)
                }
                client.capture(AiEvent.SPAN, properties)
            }
        }
    }

    override fun generation(record: GenerationRecord) {
        val properties = buildMap<String, Any> {
            put(AiProperty.TRACE_ID, record.parent.traceId.value)
            put(AiProperty.SPAN_ID, idGenerator.newId())
            put(AiProperty.PARENT_ID, record.parent.spanId.value)
            put(AiProperty.MODEL, record.modelId)
            put(AiProperty.PROVIDER, record.providerId)
            put(AiProperty.INPUT_TOKENS, record.usage.inputTokens)
            put(AiProperty.OUTPUT_TOKENS, record.usage.outputTokens)
            put(AiProperty.CACHE_READ_TOKENS, record.usage.cacheReadTokens)
            put(AiProperty.CACHE_WRITE_TOKENS, record.usage.cacheWriteTokens)
            put(AiProperty.LATENCY_SECONDS, record.latencyMillis / MILLIS_PER_SECOND)
            put(AiProperty.IS_ERROR, record.errorType != null)
            record.stopReason?.let { put(AiProperty.STOP_REASON, it) }
            record.errorType?.let { put(AiProperty.ERROR, it) }
        }
        client.capture(AiEvent.GENERATION, properties)
    }

    private fun secondsSince(startedAt: Long): Double = (clock.nowMillis() - startedAt) / MILLIS_PER_SECOND

    private fun Attributes.toProperties(): Map<String, Any> = values.mapValues { (_, value) ->
        when (value) {
            is AttributeValue.Text -> value.value
            is AttributeValue.Number -> value.value
            is AttributeValue.Flag -> value.value
        }
    }

    /** PostHog LLM analytics event and property names. */
    private object AiEvent {
        const val SPAN = "\$ai_span"
        const val GENERATION = "\$ai_generation"
    }

    private object AiProperty {
        const val TRACE_ID = "\$ai_trace_id"
        const val SPAN_ID = "\$ai_span_id"
        const val PARENT_ID = "\$ai_parent_id"
        const val SPAN_NAME = "\$ai_span_name"
        const val LATENCY_SECONDS = "\$ai_latency"
        const val IS_ERROR = "\$ai_is_error"
        const val ERROR = "\$ai_error"
        const val MODEL = "\$ai_model"
        const val PROVIDER = "\$ai_provider"
        const val INPUT_TOKENS = "\$ai_input_tokens"
        const val OUTPUT_TOKENS = "\$ai_output_tokens"
        const val CACHE_READ_TOKENS = "\$ai_cache_read_input_tokens"
        const val CACHE_WRITE_TOKENS = "\$ai_cache_creation_input_tokens"
        const val STOP_REASON = "\$ai_stop_reason"
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
