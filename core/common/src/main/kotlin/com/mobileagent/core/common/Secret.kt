package com.mobileagent.core.common

/** Wraps a sensitive string so it can't leak through toString(), logs or telemetry. */
class Secret(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = REDACTED

    override fun equals(other: Any?): Boolean = other is Secret && other.value == value

    override fun hashCode(): Int = value.hashCode()

    private companion object {
        const val REDACTED = "Secret(***)"
    }
}
