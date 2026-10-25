package app.whittle.chat

import app.whittle.llm.api.ModelDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Waits for a chat to go quiet, then runs [run] for it, once. Leaving a chat starts the wait; coming back to it, or
 * writing in it, ends it, so a chat the user is still in is never reviewed.
 */
class QuietReviews(
    private val scope: CoroutineScope,
    private val quietMillis: Long,
    private val run: suspend (chatId: String) -> Unit,
) {
    private val pending = mutableMapOf<String, Job>()

    /** The user left [chatId]: it is reviewed after [quietMillis], counted from now. */
    fun left(chatId: String) {
        pending.remove(chatId)?.cancel()
        pending[chatId] = scope.launch {
            delay(quietMillis)
            pending.remove(chatId)
            run(chatId)
        }
    }

    /** The user is back in [chatId]: no review is waiting for it. */
    fun returned(chatId: String) {
        pending.remove(chatId)?.cancel()
    }
}

/**
 * Hands [review] a chat's words. A chat an automation started is passed as one, so it is never reviewed: its first
 * message is Whittle's own prompt, not something the user said.
 */
suspend fun reviewChat(
    chatId: String,
    items: List<ThreadItem>,
    model: ModelDescriptor?,
    automationName: String?,
    review: suspend (String, List<ThreadItem>, ModelDescriptor?, Boolean) -> Unit,
) = review(chatId, items, model, automationName != null)

/**
 * Whether a chat whose task just ended starts going quiet: not while it's working, and not while the user is looking
 * at it (the chat on screen in a visible app); one they can't see, in another chat or with the app left, is.
 */
fun goesQuietWhenIdle(working: Boolean, onScreen: Boolean, appVisible: Boolean): Boolean = !working && (!onScreen || !appVisible)
