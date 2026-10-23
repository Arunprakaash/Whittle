package app.whittle.claudecode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClaudeCodeStatusTest {
    private val plans = mapOf("max" to "Max", "pro" to "Pro")

    @Test
    fun `the signed-in account and plan are read from the status`() {
        val status = """{"loggedIn":true,"authMethod":"claude.ai","email":"sam@example.com","subscriptionType":"max"}"""
        assertEquals(ClaudeCodeAccount("sam@example.com", "Max"), ClaudeCodeStatus.account(status, plans))
    }

    @Test
    fun `an unknown plan keeps its own name, and a status naming neither gives no account`() {
        assertEquals(ClaudeCodeAccount(null, "Studio"), ClaudeCodeStatus.account("""{"subscriptionType":"studio"}""", plans))
        assertNull(ClaudeCodeStatus.account("""{"loggedIn":true,"authMethod":"claude.ai"}""", plans))
        assertNull(ClaudeCodeStatus.account("Logged in", plans))
    }
}
