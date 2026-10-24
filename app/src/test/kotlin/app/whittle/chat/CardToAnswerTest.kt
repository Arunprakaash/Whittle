package app.whittle.chat

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.storage.chats.ChatId
import app.whittle.tools.presentation.DecisionText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CardToAnswerTest {
    private val send = DecisionText("sms", "Send?", "Opens Messages.", "Hi", "Priya", "Send", "send", "Don't send", "Sent", "Not sent")
    private val delete = DecisionText("delete", "Delete?", "Deletes the file.", null, null, "Delete", "delete", "Keep", "Deleted", "Kept")
    private val chatA = ChatId("chat-a")
    private val chatB = ChatId("chat-b")

    // Both chats' providers called their first tool call "call_0", so the two cards share a key.
    private val deleteInA = ThreadItem.Decision("decision-call_0", RunId("run-a"), ToolCallId("call_0"), delete, fromNotification = false)
    private val sendInB = ThreadItem.Decision("decision-call_0", RunId("run-b"), ToolCallId("call_0"), send, fromNotification = true)

    /** The open chat first, then the ones working in the background, as the view model passes them. */
    private fun find(chats: List<Pair<ChatId?, List<ThreadItem>>>, chat: ChatId, key: String) =
        cardToAnswer(chats, chat, key, { it.first }, { it.second })?.second

    @Test
    fun `the answer lands on the card in the chat the notification names`() {
        val chats = listOf(chatA to listOf(deleteInA), chatB to listOf(sendInB))
        assertEquals(sendInB, find(chats, chatB, "decision-call_0"))
    }

    @Test
    fun `a card with the same key in another chat is never answered`() {
        // Chat B's card was answered; its notification's Approve must not reach chat A's delete.
        val answered = ThreadItem.Resolved("decision-call_0", "Sent", declined = false)
        val chats = listOf(chatA to listOf(deleteInA.copy(fromNotification = true)), chatB to listOf(answered))
        assertNull(find(chats, chatB, "decision-call_0"))
    }

    @Test
    fun `a chat that has closed answers nothing`() {
        assertNull(find(listOf(chatA to listOf(deleteInA)), chatB, "decision-call_0"))
    }

    @Test
    fun `a card already answered is not answered again`() {
        val answered = ThreadItem.Resolved("decision-call_0", "Sent", declined = false)
        assertNull(find(listOf(chatB to listOf(answered)), chatB, "decision-call_0"))
    }

    @Test
    fun `a card only the app may answer is left for the app`() {
        assertNull(find(listOf(chatA to listOf(deleteInA)), chatA, "decision-call_0"))
    }

    @Test
    fun `a chat working in the background is found as well as the open one`() {
        val open = null to listOf<ThreadItem>(ThreadItem.User("u1", "Hello", steer = false))
        assertEquals(sendInB, find(listOf(open, chatB to listOf(sendInB)), chatB, "decision-call_0"))
    }
}
