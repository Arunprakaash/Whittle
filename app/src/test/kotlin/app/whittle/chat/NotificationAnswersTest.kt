package app.whittle.chat

import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.storage.chats.ChatId
import app.whittle.tools.presentation.DecisionText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Answers from a notification across the chats' life cycle, as ChatViewModel wires it: the chat on screen, a chat working
 * in the background, and a chat whose run paused on the card, so its session was let go and only the saved chat is left.
 */
class NotificationAnswersTest {
    private val text = DecisionText("sms", "Send?", "Opens Messages.", "Hi", "Priya", "Send", "send", "Don't send", "Sent", "Not sent")
    private val chatA = ChatId("chat-a")
    private val chatB = ChatId("chat-b")
    private val key = "decision-run-1-call_0"

    /** A chat's session: a tap answers the card the way ChatSession does, replacing it with its outcome. */
    private class FakeChat(override val chatId: ChatId, cards: List<ThreadItem>) : DecisionChat {
        override var items: List<ThreadItem> = cards
        val answers = mutableListOf<Pair<String, Boolean>>()
        var closed = false

        override fun answer(item: ThreadItem.Decision, approve: Boolean) {
            answers += item.key to approve
            items = items.map { if (it.key == item.key) ThreadItem.Resolved(item.key, "done", declined = !approve) else it }
        }
    }

    private fun card(fromNotification: Boolean = true) = ThreadItem.Decision(key, RunId("run-1"), ToolCallId("call_0"), text, fromNotification)

    /** The view model's chats: one on screen, those working in the background, and those only saved. */
    private inner class Chats(var current: FakeChat, saved: Map<ChatId, List<ThreadItem>> = emptyMap()) {
        val background = mutableMapOf<ChatId, FakeChat>()
        val saved = saved.toMutableMap()
        val loaded = mutableListOf<FakeChat>()
        var openedWhileLoading: FakeChat? = null

        val answers = NotificationAnswers(
            ready = {},
            live = { listOf(current) + background.values },
            load = { id ->
                val chat = saved[id]?.let { FakeChat(id, it) }?.also(loaded::add)
                openedWhileLoading?.let { current = it }
                chat
            },
            keepWorking = { id, chat -> background[id] = chat },
            close = { it.closed = true },
        )
    }

    @Test
    fun `the chat on screen answers in place`() = runTest {
        val chats = Chats(FakeChat(chatA, listOf(card())))
        assertTrue(chats.answers.answer(chatA, key, approve = true))
        assertEquals(listOf(key to true), chats.current.answers)
        assertTrue(chats.loaded.isEmpty())
    }

    @Test
    fun `a chat working in the background answers in place`() = runTest {
        val chats = Chats(FakeChat(chatA, emptyList()))
        val working = FakeChat(chatB, listOf(card()))
        chats.background[chatB] = working
        assertTrue(chats.answers.answer(chatB, key, approve = false))
        assertEquals(listOf(key to false), working.answers)
    }

    @Test
    fun `a chat whose run paused on the card is loaded, answered, and kept working`() = runTest {
        // Its session went when the run paused; only the saved chat holds the card now.
        val chats = Chats(FakeChat(chatA, listOf(card())), saved = mapOf(chatB to listOf(card())))
        assertTrue(chats.answers.answer(chatB, key, approve = true))
        val loaded = chats.loaded.single()
        assertEquals(listOf(key to true), loaded.answers, "answered as a tap in that chat")
        assertEquals(loaded, chats.background[chatB], "kept working in the background while its run goes on")
        assertTrue(chats.current.answers.isEmpty(), "the chat on screen, with a card under the same key, is untouched")
        assertFalse(loaded.closed)
    }

    @Test
    fun `a saved chat whose card was answered already, or is kept for the app, answers nothing`() = runTest {
        val answered = ThreadItem.Resolved(key, "Sent", declined = false)
        val chats = Chats(FakeChat(chatA, emptyList()), saved = mapOf(chatB to listOf(answered), ChatId("chat-c") to listOf(card(fromNotification = false))))
        assertFalse(chats.answers.answer(chatB, key, approve = true))
        assertFalse(chats.answers.answer(ChatId("chat-c"), key, approve = true))
        assertTrue(chats.loaded.all { it.closed && it.answers.isEmpty() })
        assertTrue(chats.background.isEmpty())
    }

    @Test
    fun `a deleted chat answers nothing`() = runTest {
        val chats = Chats(FakeChat(chatA, listOf(card())))
        assertFalse(chats.answers.answer(chatB, key, approve = true))
        assertTrue(chats.current.answers.isEmpty())
    }

    @Test
    fun `a chat the user opened while it loaded is answered there, not twice`() = runTest {
        val chats = Chats(FakeChat(chatA, emptyList()), saved = mapOf(chatB to listOf(card())))
        val opened = FakeChat(chatB, listOf(card()))
        chats.openedWhileLoading = opened
        assertTrue(chats.answers.answer(chatB, key, approve = true))
        assertEquals(listOf(key to true), opened.answers)
        assertTrue(chats.loaded.single().let { it.closed && it.answers.isEmpty() })
        assertTrue(chats.background.isEmpty())
    }

    @Test
    fun `a card answered in an open chat is not answered again from its notification`() = runTest {
        val chats = Chats(FakeChat(chatA, listOf(card())), saved = mapOf(chatA to listOf(card())))
        assertTrue(chats.answers.answer(chatA, key, approve = true))
        assertFalse(chats.answers.answer(chatA, key, approve = false))
        assertEquals(listOf(key to true), chats.current.answers)
        assertTrue(chats.loaded.isEmpty(), "an open chat is never loaded a second time")
    }
}
