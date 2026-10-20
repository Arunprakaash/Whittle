package app.whittle.policy

import app.whittle.llm.api.TrustTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolId
import kotlinx.serialization.Serializable

/** Everything the engine needs to know about the run a tool call belongs to. */
data class RunContext(
    val mode: RunMode,
    /** True once untrusted content (web, email, files, connector output) has entered the run. */
    val tainted: Boolean,
    val modelTrustTier: TrustTier,
    /** Tools the user approved when setting up an automation or scheduled agent. */
    val preApprovedTools: Set<ToolId>,
    /** Tools the user already allowed once in this run ("ask once" tier). */
    val approvedOnceTools: Set<ToolId>,
)

@Serializable
enum class ApprovalScope {
    /** One approval covers the rest of the run for this tool. */
    ONCE_PER_RUN,

    /** Every call needs its own approval. */
    EVERY_CALL,
}

sealed interface PolicyDecision {
    val reason: DecisionReason

    data class Allow(override val reason: DecisionReason) : PolicyDecision

    data class Ask(override val reason: DecisionReason, val scope: ApprovalScope) : PolicyDecision

    data class Deny(override val reason: DecisionReason) : PolicyDecision
}

/** Why a decision was made. Recorded in the audit log and telemetry; never shown raw to users. */
sealed interface DecisionReason {
    data class HardDenied(val matcher: ToolMatcher) : DecisionReason

    data class Rule(val ruleId: String) : DecisionReason

    data object UnattendedNotPreApproved : DecisionReason

    data object TaintedOutbound : DecisionReason

    data class TrustTierDenied(val tier: TrustTier) : DecisionReason

    data class TrustTierRequiresApproval(val tier: TrustTier) : DecisionReason

    data object PreApproved : DecisionReason

    data object ApprovedEarlierInRun : DecisionReason

    data class RiskTierDefault(val decision: DefaultDecision) : DecisionReason
}

fun interface PolicyEngine {
    fun evaluate(tool: ToolDefinition, context: RunContext): PolicyDecision
}
