package com.mobileagent.core.model

import kotlinx.serialization.Serializable

/**
 * Where a piece of content came from. Untrusted content (web pages, email, files, connector output)
 * may inform the model but can never authorise an action, and marks the run as tainted.
 */
@Serializable
sealed interface Provenance {
    @Serializable
    data object Trusted : Provenance

    @Serializable
    data class Untrusted(val sourceLabel: String) : Provenance
}
