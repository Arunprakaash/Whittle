package app.whittle.chat

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.presentation.DecisionText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WaitingDecisionTest {
    private val text = DecisionText("sms", "Send?", "Opens Messages.", "Hi", "Priya", "Send", "send", "Don't send", "Sent", "Not sent")
    private val card = ThreadItem.Decision("decision-c1", RunId("run-1"), ToolCallId("c1"), text)

    @Test
    fun `a waiting card is found by its key`() {
        assertEquals(card, waitingDecision(listOf(ThreadItem.User("u1", "Text Priya", steer = false), card), "decision-c1"))
    }

    @Test
    fun `an answered card is not answered again from its notification`() {
        assertNull(waitingDecision(listOf(ThreadItem.Resolved("decision-c1", "Sent", declined = false)), "decision-c1"))
    }

    @Test
    fun `a card that isn't in the open chat is not found`() {
        assertNull(waitingDecision(listOf(card), "decision-c2"))
        assertNull(waitingDecision(emptyList(), "decision-c1"))
    }
}
