package app.whittle.chat

import app.whittle.storage.chats.ChatId

/** A chat as an answer from a notification sees it: its id, its cards, and the tap on one. [ChatSession] is one. */
interface DecisionChat {
    val chatId: ChatId?
    val items: List<ThreadItem>

    fun answer(item: ThreadItem.Decision, approve: Boolean)
}

/**
 * Applies Approve or Decline from an approval card's notification to the card [cardToAnswer] finds, through the chats'
 * life cycle. The chat on screen, or one working in the background, answers in place. A chat that has gone quiet is
 * loaded from storage and answered as a tap there would be: a run that asks for approval pauses, and its session is let go
 * once the user isn't looking at it, so that is the usual case for an automation. Answering resumes the run, so the loaded
 * chat is kept working in the background until the run ends, like any other.
 */
internal class NotificationAnswers<S : DecisionChat>(
    /** Waits until the chats the app restores at launch are in place, so a loaded chat isn't loaded twice. */
    private val ready: suspend () -> Unit,
    /** The chat on screen first, then those working in the background. */
    private val live: () -> List<S>,
    /** The saved chat [ChatId] in a session of its own; null when it's gone. */
    private val load: suspend (ChatId) -> S?,
    /** Keeps a loaded chat whose run was resumed among those working in the background. */
    private val keepWorking: (ChatId, S) -> Unit,
    /** Lets go of a loaded chat that had nothing to answer. */
    private val close: (S) -> Unit,
) {
    /** True when the card [key] in [chat] got the answer; false when it was answered already, is gone, or is kept for the app. */
    suspend fun answer(chat: ChatId, key: String, approve: Boolean): Boolean {
        ready()
        answerLive(chat, key, approve)?.let { return it }
        val loaded = load(chat) ?: return false
        // The user may have opened the chat while it loaded: then that session is the one to answer.
        answerLive(chat, key, approve)?.let { answered ->
            close(loaded)
            return answered
        }
        val (_, card) = cardToAnswer(listOf(loaded), chat, key, { it.chatId }, { it.items }) ?: run {
            close(loaded)
            return false
        }
        keepWorking(chat, loaded)
        loaded.answer(card, approve)
        return true
    }

    /** Null when no live session shows [chat]; otherwise whether its card [key] got the answer. */
    private fun answerLive(chat: ChatId, key: String, approve: Boolean): Boolean? {
        val sessions = live()
        if (sessions.none { it.chatId == chat }) return null
        val (session, card) = cardToAnswer(sessions, chat, key, { it.chatId }, { it.items }) ?: return false
        session.answer(card, approve)
        return true
    }
}
