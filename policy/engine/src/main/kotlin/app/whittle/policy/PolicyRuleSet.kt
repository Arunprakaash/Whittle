package app.whittle.policy

import app.whittle.core.config.VersionedConfig
import app.whittle.llm.api.GlobPattern
import app.whittle.llm.api.TrustTier
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import kotlinx.serialization.Serializable

/** Policy as reviewed data. Order of evaluation is fixed in [RuleBasedPolicyEngine]. */
@Serializable
data class PolicyRuleSet(
    override val schemaVersion: Int,
    /** Never allowed, whatever the user or model says. */
    val hardDeny: List<ToolMatcher>,
    val rules: List<PolicyRule>,
    val riskTierDefaults: Map<RiskTier, DefaultDecision>,
    val trustTierLimits: Map<TrustTier, TrustTierLimit>,
) : VersionedConfig {
    init {
        val missingTiers = RiskTier.entries - riskTierDefaults.keys
        require(missingTiers.isEmpty()) { "riskTierDefaults is missing $missingTiers" }
        val missingTrust = TrustTier.entries - trustTierLimits.keys
        require(missingTrust.isEmpty()) { "trustTierLimits is missing $missingTrust" }
    }

    /**
     * Whether an ask rule covers [tool] in a run that has ([tainted]) or hasn't read untrusted content, in any run mode:
     * the calls this policy deliberately always puts to the user.
     */
    fun alwaysAsks(tool: ToolDefinition, tainted: Boolean): Boolean =
        rules.any { it.effect == RuleEffect.ASK && (it.whenTainted == null || it.whenTainted == tainted) && it.matcher.matches(tool) }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

@Serializable
data class ToolMatcher(
    /** Tool id pattern; `*` matches any run of characters. Null matches every tool. */
    val toolIdPattern: String? = null,
    val riskTiers: Set<RiskTier>? = null,
    val executionSites: Set<ExecutionSite>? = null,
) {
    fun matches(tool: ToolDefinition): Boolean =
        (toolIdPattern == null || GlobPattern(toolIdPattern).matches(tool.id.value)) &&
            (riskTiers == null || tool.riskTier in riskTiers) &&
            (executionSites == null || tool.executionSite in executionSites)
}

@Serializable
enum class RuleEffect { ALLOW, ASK, DENY }

@Serializable
enum class RunMode {
    /** The user is present and can answer approval prompts in the app. */
    LIVE,

    /** Automations and scheduled agents: only pre-approved tools run without a notification approval. */
    UNATTENDED,
}

@Serializable
data class PolicyRule(
    val id: String,
    val effect: RuleEffect,
    val matcher: ToolMatcher,
    /** Run modes this rule applies to. Null applies to all modes. */
    val modes: Set<RunMode>? = null,
    /**
     * True: only once the run has read outside content (web pages, files, connected services), which could be
     * steering the model. False: only before. Null: either way.
     */
    val whenTainted: Boolean? = null,
) {
    fun appliesTo(tool: ToolDefinition, context: RunContext): Boolean =
        (modes == null || context.mode in modes) && (whenTainted == null || whenTainted == context.tainted) && matcher.matches(tool)
}

@Serializable
enum class DefaultDecision { ALLOW, ASK_ONCE, ASK_EVERY_TIME }

@Serializable
data class TrustTierLimit(
    /** Risk tiers a model at this trust tier may never use. */
    val deniedRiskTiers: Set<RiskTier>,
    /** From this risk tier upwards, every call needs approval for a model at this trust tier. */
    val askEveryTimeFrom: RiskTier,
)
