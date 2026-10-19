package com.mobileagent.core.model

import kotlinx.serialization.Serializable

@Serializable
data class TokenUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens

    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
    )

    companion object {
        val Zero = TokenUsage(inputTokens = 0, outputTokens = 0, cacheReadTokens = 0, cacheWriteTokens = 0)
    }
}
