package com.mobileagent.observability

import com.mobileagent.core.model.TokenUsage

@JvmInline
value class TraceId(val value: String)

@JvmInline
value class SpanId(val value: String)

data class SpanContext(val traceId: TraceId, val spanId: SpanId)

enum class SpanStatus { OK, ERROR }

/** Product and diagnostic events. Wire names are what appears in the analytics tool. */
enum class TelemetryEvent(val wireName: String) {
    RUN_STARTED("run_started"),
    RUN_RESUMED("run_resumed"),
    RUN_COMPLETED("run_completed"),
    RUN_FAILED("run_failed"),
    RUN_PAUSED("run_paused"),
    POLICY_DECISION("policy_decision"),
    APPROVAL_ANSWERED("approval_answered"),
    TOOL_FAILED("tool_failed"),
}

enum class SpanName(val wireName: String) {
    RUN("agent_run"),
    STEP("agent_step"),
    TOOL_CALL("tool_call"),
}

/** One model call, exported as an LLM generation in the analytics tool. */
data class GenerationRecord(
    val parent: SpanContext,
    val modelId: String,
    val providerId: String,
    val usage: TokenUsage,
    val latencyMillis: Long,
    val stopReason: String?,
    val errorType: String?,
)

interface Span {
    val context: SpanContext

    fun end(status: SpanStatus, attributes: Attributes = Attributes.Empty)
}

/** Vendor-neutral telemetry. PostHog is one implementation; the harness never imports a vendor SDK. */
interface Telemetry {
    fun event(event: TelemetryEvent, attributes: Attributes = Attributes.Empty)

    fun startSpan(name: SpanName, parent: SpanContext?, attributes: Attributes = Attributes.Empty): Span

    fun generation(record: GenerationRecord)
}
