package app.whittle.policy

import app.whittle.core.model.Provenance
import app.whittle.llm.api.TrustTier
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolId
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RuleBasedPolicyEngineTest {
    private val baseRules = PolicyRuleSet(
        schemaVersion = PolicyRuleSet.SCHEMA_VERSION,
        hardDeny = listOf(ToolMatcher(toolIdPattern = "device.sms.*")),
        rules = emptyList(),
        riskTierDefaults = mapOf(
            RiskTier.READ to DefaultDecision.ALLOW,
            RiskTier.WORKSPACE to DefaultDecision.ALLOW,
            RiskTier.WEB_READ to DefaultDecision.ALLOW,
            RiskTier.WRITE_LOCAL to DefaultDecision.ALLOW,
            RiskTier.NETWORK_OUT to DefaultDecision.ASK_ONCE,
            RiskTier.SEND_OR_SHARE to DefaultDecision.ASK_EVERY_TIME,
            RiskTier.DELETE_OR_PAY to DefaultDecision.ASK_EVERY_TIME,
        ),
        trustTierLimits = mapOf(
            TrustTier.A to TrustTierLimit(deniedRiskTiers = emptySet(), askEveryTimeFrom = RiskTier.SEND_OR_SHARE),
            TrustTier.B to TrustTierLimit(deniedRiskTiers = emptySet(), askEveryTimeFrom = RiskTier.NETWORK_OUT),
            TrustTier.C to TrustTierLimit(deniedRiskTiers = setOf(RiskTier.SEND_OR_SHARE, RiskTier.DELETE_OR_PAY), askEveryTimeFrom = RiskTier.WRITE_LOCAL),
        ),
    )
    private val engine = RuleBasedPolicyEngine(baseRules)

    @Test
    fun `hard deny wins over everything`() {
        val allowAll = baseRules.copy(rules = listOf(PolicyRule("allow-all", RuleEffect.ALLOW, ToolMatcher())))
        val decision = RuleBasedPolicyEngine(allowAll).evaluate(tool("device.sms.send", RiskTier.SEND_OR_SHARE), context())
        assertIs<DecisionReason.HardDenied>(assertIs<PolicyDecision.Deny>(decision).reason)
    }

    @Test
    fun `deny rule beats a later allow rule`() {
        val rules = baseRules.copy(
            rules = listOf(
                PolicyRule("allow-files", RuleEffect.ALLOW, ToolMatcher(toolIdPattern = "files.*")),
                PolicyRule("deny-delete", RuleEffect.DENY, ToolMatcher(toolIdPattern = "files.delete")),
            ),
        )
        assertIs<PolicyDecision.Deny>(RuleBasedPolicyEngine(rules).evaluate(tool("files.delete", RiskTier.WRITE_LOCAL), context()))
    }

    @Test
    fun `read tools are allowed by default`() {
        assertIs<PolicyDecision.Allow>(engine.evaluate(tool("time.now", RiskTier.READ), context()))
    }

    @Test
    fun `network tools ask once per run`() {
        val decision = assertIs<PolicyDecision.Ask>(engine.evaluate(tool("web.fetch", RiskTier.NETWORK_OUT), context()))
        assertEquals(ApprovalScope.ONCE_PER_RUN, decision.scope)

        val approved = context(approvedOnce = setOf(ToolId("web.fetch")))
        assertIs<PolicyDecision.Allow>(engine.evaluate(tool("web.fetch", RiskTier.NETWORK_OUT), approved))
    }

    @Test
    fun `tainted run must ask for outbound tools even if approved once`() {
        val tainted = context(tainted = true, approvedOnce = setOf(ToolId("web.fetch")))
        val decision = assertIs<PolicyDecision.Ask>(engine.evaluate(tool("web.fetch", RiskTier.NETWORK_OUT), tainted))
        assertEquals(DecisionReason.TaintedOutbound, decision.reason)
        assertEquals(ApprovalScope.EVERY_CALL, decision.scope)
    }

    @Test
    fun `web reads run without asking, even for a tier C model on a tainted run`() {
        assertIs<PolicyDecision.Allow>(engine.evaluate(tool("web_search", RiskTier.WEB_READ), context(tier = TrustTier.C)))
        assertIs<PolicyDecision.Allow>(engine.evaluate(tool("web_search", RiskTier.WEB_READ), context(tier = TrustTier.C, tainted = true)))
        assertIs<PolicyDecision.Allow>(engine.evaluate(tool("web_search", RiskTier.WEB_READ), context(tier = TrustTier.C, personalData = true)))
    }

    @Test
    fun `web reads ask once untrusted content and personal data are both in the run`() {
        val decision = engine.evaluate(tool("web_search", RiskTier.WEB_READ), context(tainted = true, personalData = true))
        val ask = assertIs<PolicyDecision.Ask>(decision)
        assertEquals(DecisionReason.TaintedWebReadWithPersonalData, ask.reason)
        assertEquals(ApprovalScope.EVERY_CALL, ask.scope)
    }

    @Test
    fun `tier C model is denied send tools`() {
        val decision = engine.evaluate(tool("share.text", RiskTier.SEND_OR_SHARE), context(tier = TrustTier.C))
        assertIs<DecisionReason.TrustTierDenied>(assertIs<PolicyDecision.Deny>(decision).reason)
    }

    @Test
    fun `tier C model must ask for local writes`() {
        val decision = engine.evaluate(tool("notes.write", RiskTier.WRITE_LOCAL), context(tier = TrustTier.C))
        assertIs<DecisionReason.TrustTierRequiresApproval>(assertIs<PolicyDecision.Ask>(decision).reason)
    }

    @Test
    fun `unattended runs only allow pre-approved tools`() {
        val unattended = context(mode = RunMode.UNATTENDED, preApproved = setOf(ToolId("time.now")))
        assertIs<PolicyDecision.Allow>(engine.evaluate(tool("time.now", RiskTier.READ), unattended))
        val other = assertIs<PolicyDecision.Ask>(engine.evaluate(tool("notes.read", RiskTier.READ), unattended))
        assertEquals(DecisionReason.UnattendedNotPreApproved, other.reason)
    }

    @Test
    fun `unattended pre-approved outbound tool still asks once the run is tainted`() {
        val unattended = context(mode = RunMode.UNATTENDED, tainted = true, preApproved = setOf(ToolId("web.fetch")))
        assertIs<PolicyDecision.Ask>(engine.evaluate(tool("web.fetch", RiskTier.NETWORK_OUT), unattended))
    }

    private fun tool(id: String, risk: RiskTier) = ToolDefinition(
        id = ToolId(id),
        description = id,
        inputSchema = JsonObject(emptyMap()),
        riskTier = risk,
        executionSite = ExecutionSite.DEVICE,
        requiredCapabilities = emptySet(),
        consentTextKey = "consent_$id",
        outputProvenance = Provenance.Trusted,
    )

    private fun context(
        mode: RunMode = RunMode.LIVE,
        tainted: Boolean = false,
        tier: TrustTier = TrustTier.A,
        preApproved: Set<ToolId> = emptySet(),
        approvedOnce: Set<ToolId> = emptySet(),
        personalData: Boolean = false,
    ) = RunContext(mode, tainted, tier, preApproved, approvedOnce, personalData)
}
