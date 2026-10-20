package app.whittle.tools.presentation

import app.whittle.core.config.VersionedConfig
import kotlinx.serialization.Serializable

/**
 * How each tool appears to the user: an icon, the step-row label while it runs and after it finishes,
 * and the decision card when it needs the user's choice. Plain language only; tool ids and arguments
 * are never shown raw. Placeholders are the call's argument names, plus `<argument>_host` for URLs.
 */
@Serializable
data class ToolPresentationConfig(
    override val schemaVersion: Int,
    /** Used for a tool that has no entry, so a missing entry never shows a tool id. */
    val fallback: ToolPresentation,
    val tools: Map<String, ToolPresentation>,
) : VersionedConfig {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class ToolPresentation(
    /** Material Symbols name; the app maps it to a bundled vector icon. */
    val icon: String,
    val running: String,
    val done: String,
    /** Required for any tool the policy engine may ask about. */
    val decision: DecisionPresentation? = null,
)

@Serializable
data class DecisionPresentation(
    val icon: String,
    val title: String,
    val body: String,
    /** Argument shown verbatim on the card: the exact message, address or amount. */
    val payloadArgument: String? = null,
    /** Argument shown above the payload, such as the recipient. */
    val payloadLabelArgument: String? = null,
    val approve: String,
    val approveIcon: String,
    val decline: String,
    /** One-line result the card collapses to after each answer. */
    val approvedOutcome: String,
    val declinedOutcome: String,
)
