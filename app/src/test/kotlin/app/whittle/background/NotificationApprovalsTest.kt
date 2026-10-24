package app.whittle.background

import app.whittle.core.model.Provenance
import app.whittle.settings.NotificationApprovalDefaults
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolId
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationApprovalsTest {
    private val approvals = NotificationApprovalDefaults(fromNotification = true, inAppOnlyTiers = setOf(RiskTier.DELETE_OR_PAY), inAppOnlyTools = setOf("open_app"))

    @Test
    fun `a tier kept in the app gets no buttons, others do`() {
        assertFalse(approvals.buttonsFor(tool("delete", RiskTier.DELETE_OR_PAY), tainted = false, alwaysAsks = false))
        assertTrue(approvals.buttonsFor(tool("send", RiskTier.SEND_OR_SHARE), tainted = false, alwaysAsks = false))
        assertTrue(approvals.buttonsFor(tool("write", RiskTier.WRITE_LOCAL), tainted = false, alwaysAsks = false))
    }

    @Test
    fun `a tool kept in the app gets no buttons whatever its tier`() {
        assertFalse(approvals.buttonsFor(tool("open_app", RiskTier.WRITE_LOCAL), tainted = false, alwaysAsks = false))
    }

    @Test
    fun `a call the policy always asks about is decided in the app`() {
        assertFalse(approvals.buttonsFor(tool("schedule_task", RiskTier.WRITE_LOCAL), tainted = false, alwaysAsks = true))
    }

    @Test
    fun `nothing from a run that read untrusted content gets buttons`() {
        assertFalse(approvals.buttonsFor(tool("send", RiskTier.SEND_OR_SHARE), tainted = true, alwaysAsks = false))
    }

    @Test
    fun `turned off, no approval gets buttons`() {
        val off = approvals.copy(fromNotification = false, inAppOnlyTiers = emptySet(), inAppOnlyTools = emptySet())
        assertTrue(RiskTier.entries.none { off.buttonsFor(tool("any", it), tainted = false, alwaysAsks = false) })
    }

    private fun tool(id: String, tier: RiskTier) = ToolDefinition(
        id = ToolId(id),
        description = id,
        inputSchema = JsonObject(emptyMap()),
        riskTier = tier,
        executionSite = ExecutionSite.DEVICE,
        requiredCapabilities = emptySet(),
        consentTextKey = "consent_$id",
        outputProvenance = Provenance.Trusted,
    )
}
