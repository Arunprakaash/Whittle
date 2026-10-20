package app.whittle.policy

import app.whittle.tools.api.ToolDefinition

/**
 * Deterministic policy, independent of the model. Fixed order:
 * 1. hard denylist  2. deny rules  3. model trust tier denials  4. tainted run + outbound tool
 * 5. unattended runs (pre-approved tools only)  6. trust tier approval threshold  7. ask rules
 * 8. allow rules  9. risk-tier default.
 * Deny always wins; nothing later in the order can turn a deny into an allow.
 */
class RuleBasedPolicyEngine(private val ruleSet: PolicyRuleSet) : PolicyEngine {
    override fun evaluate(tool: ToolDefinition, context: RunContext): PolicyDecision {
        ruleSet.hardDeny.firstOrNull { it.matches(tool) }?.let {
            return PolicyDecision.Deny(DecisionReason.HardDenied(it))
        }
        firstRule(RuleEffect.DENY, tool, context)?.let {
            return PolicyDecision.Deny(DecisionReason.Rule(it.id))
        }

        val trustLimit = ruleSet.trustTierLimits.getValue(context.modelTrustTier)
        if (tool.riskTier in trustLimit.deniedRiskTiers) {
            return PolicyDecision.Deny(DecisionReason.TrustTierDenied(context.modelTrustTier))
        }

        if (context.tainted && tool.riskTier.isOutbound) {
            return PolicyDecision.Ask(DecisionReason.TaintedOutbound, ApprovalScope.EVERY_CALL)
        }
        if (context.mode == RunMode.UNATTENDED) {
            return if (tool.id in context.preApprovedTools) {
                PolicyDecision.Allow(DecisionReason.PreApproved)
            } else {
                PolicyDecision.Ask(DecisionReason.UnattendedNotPreApproved, ApprovalScope.EVERY_CALL)
            }
        }

        if (tool.riskTier >= trustLimit.askEveryTimeFrom) {
            return PolicyDecision.Ask(DecisionReason.TrustTierRequiresApproval(context.modelTrustTier), ApprovalScope.EVERY_CALL)
        }

        firstRule(RuleEffect.ASK, tool, context)?.let {
            return PolicyDecision.Ask(DecisionReason.Rule(it.id), ApprovalScope.EVERY_CALL)
        }
        firstRule(RuleEffect.ALLOW, tool, context)?.let {
            return PolicyDecision.Allow(DecisionReason.Rule(it.id))
        }
        return riskTierDefault(tool, context)
    }

    private fun firstRule(effect: RuleEffect, tool: ToolDefinition, context: RunContext): PolicyRule? =
        ruleSet.rules.firstOrNull { it.effect == effect && it.appliesTo(tool, context.mode) }

    private fun riskTierDefault(tool: ToolDefinition, context: RunContext): PolicyDecision {
        val default = ruleSet.riskTierDefaults.getValue(tool.riskTier)
        val reason = DecisionReason.RiskTierDefault(default)
        return when (default) {
            DefaultDecision.ALLOW -> PolicyDecision.Allow(reason)
            DefaultDecision.ASK_ONCE -> if (tool.id in context.approvedOnceTools) {
                PolicyDecision.Allow(DecisionReason.ApprovedEarlierInRun)
            } else {
                PolicyDecision.Ask(reason, ApprovalScope.ONCE_PER_RUN)
            }
            DefaultDecision.ASK_EVERY_TIME -> PolicyDecision.Ask(reason, ApprovalScope.EVERY_CALL)
        }
    }
}
