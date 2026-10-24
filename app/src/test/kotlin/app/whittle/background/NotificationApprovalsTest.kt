package app.whittle.background

import app.whittle.settings.NotificationApprovalDefaults
import app.whittle.tools.api.RiskTier
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationApprovalsTest {
    @Test
    fun `a tier kept in the app gets no buttons, others do`() {
        val approvals = NotificationApprovalDefaults(fromNotification = true, inAppOnly = setOf("DELETE_OR_PAY"))
        assertFalse(approvals.buttonsFor(RiskTier.DELETE_OR_PAY))
        assertTrue(approvals.buttonsFor(RiskTier.SEND_OR_SHARE))
        assertTrue(approvals.buttonsFor(RiskTier.WRITE_LOCAL))
    }

    @Test
    fun `turned off, no approval gets buttons`() {
        val approvals = NotificationApprovalDefaults(fromNotification = false, inAppOnly = emptySet())
        assertTrue(RiskTier.entries.none(approvals::buttonsFor))
    }

    @Test
    fun `a misspelled tier fails at load instead of quietly allowing buttons`() {
        assertFailsWith<IllegalArgumentException> { NotificationApprovalDefaults(fromNotification = true, inAppOnly = setOf("DELETE_OR_PAID")) }
    }
}
