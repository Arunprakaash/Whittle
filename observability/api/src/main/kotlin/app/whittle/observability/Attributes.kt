package app.whittle.observability

/** How sensitive an attribute is. Content is only exported when the user consented to full content. */
enum class Sensitivity {
    /** Ids, counts, durations, enum values, error types. */
    METADATA,

    /** Anything the user or a model wrote: prompts, replies, tool arguments and outputs. */
    CONTENT,
}

sealed interface AttributeValue {
    val sensitivity: Sensitivity

    data class Text(val value: String, override val sensitivity: Sensitivity) : AttributeValue

    data class Number(val value: Double) : AttributeValue {
        override val sensitivity: Sensitivity = Sensitivity.METADATA
    }

    data class Flag(val value: Boolean) : AttributeValue {
        override val sensitivity: Sensitivity = Sensitivity.METADATA
    }
}

/** Immutable attribute map. Keys are snake_case names from [AttributeKeys]. */
class Attributes private constructor(val values: Map<String, AttributeValue>) {
    operator fun plus(other: Attributes): Attributes = Attributes(values + other.values)

    fun filter(predicate: (AttributeValue) -> Boolean): Attributes = Attributes(values.filterValues(predicate))

    fun mapValues(transform: (AttributeValue) -> AttributeValue): Attributes = Attributes(values.mapValues { transform(it.value) })

    class Builder {
        private val values = linkedMapOf<String, AttributeValue>()

        fun metadata(key: String, value: String?) = apply { value?.let { values[key] = AttributeValue.Text(it, Sensitivity.METADATA) } }

        fun content(key: String, value: String?) = apply { value?.let { values[key] = AttributeValue.Text(it, Sensitivity.CONTENT) } }

        fun number(key: String, value: Number?) = apply { value?.let { values[key] = AttributeValue.Number(it.toDouble()) } }

        fun flag(key: String, value: Boolean?) = apply { value?.let { values[key] = AttributeValue.Flag(it) } }

        fun build(): Attributes = Attributes(values.toMap())
    }

    companion object {
        val Empty = Attributes(emptyMap())

        fun of(block: Builder.() -> Unit): Attributes = Builder().apply(block).build()
    }
}

/** Attribute names used across the harness, in one place so dashboards stay consistent. */
object AttributeKeys {
    const val RUN_ID = "run_id"
    const val RUN_MODE = "run_mode"
    const val STEP = "step"
    const val MODEL_ID = "model_id"
    const val PROVIDER_ID = "provider_id"
    const val MODEL_TRUST_TIER = "model_trust_tier"
    const val TOOL_ID = "tool_id"
    const val RISK_TIER = "risk_tier"
    const val EXECUTION_SITE = "execution_site"
    const val DECISION = "decision"
    const val DECISION_REASON = "decision_reason"
    const val APPROVAL_ANSWER = "approval_answer"
    const val ERROR_TYPE = "error_type"
    const val ERROR_MESSAGE = "error_message"
    const val STOP_REASON = "stop_reason"
    const val RUN_STATUS = "run_status"
    const val TAINTED = "tainted"
    const val TOTAL_TOKENS = "total_tokens"
}
