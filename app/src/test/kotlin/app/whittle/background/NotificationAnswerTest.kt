package app.whittle.background

import app.whittle.storage.chats.ChatId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationAnswerTest {
    @Test
    fun `Approve and Decline name the card and its chat`() {
        assertEquals(
            NotificationAnswer(ChatId("chat-b"), "decision-c1", approve = true),
            NotificationAnswer.from(ApprovalActivity.ACTION_APPROVE, "chat-b", "decision-c1", locked = false),
        )
        assertEquals(
            NotificationAnswer(ChatId("chat-b"), "decision-c1", approve = false),
            NotificationAnswer.from(ApprovalActivity.ACTION_DECLINE, "chat-b", "decision-c1", locked = false),
        )
    }

    @Test
    fun `a locked phone applies nothing`() {
        assertNull(NotificationAnswer.from(ApprovalActivity.ACTION_APPROVE, "chat-b", "decision-c1", locked = true))
        assertNull(NotificationAnswer.from(ApprovalActivity.ACTION_DECLINE, "chat-b", "decision-c1", locked = true))
    }

    @Test
    fun `an intent without its chat, its key or a known action applies nothing`() {
        assertNull(NotificationAnswer.from(ApprovalActivity.ACTION_APPROVE, null, "decision-c1", locked = false))
        assertNull(NotificationAnswer.from(ApprovalActivity.ACTION_APPROVE, "", "decision-c1", locked = false))
        assertNull(NotificationAnswer.from(ApprovalActivity.ACTION_APPROVE, "chat-b", null, locked = false))
        assertNull(NotificationAnswer.from("app.whittle.action.OTHER", "chat-b", "decision-c1", locked = false))
        assertNull(NotificationAnswer.from(null, "chat-b", "decision-c1", locked = false))
    }
}
