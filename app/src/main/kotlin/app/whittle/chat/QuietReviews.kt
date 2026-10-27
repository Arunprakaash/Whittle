package app.whittle.chat

import app.whittle.llm.api.ModelDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Waits for a chat to go quiet, then runs [run] for it, once. Leaving a chat starts the wait; coming back to it, writing
 * in it or deleting it ends it, so a chat the user is still in is never reviewed. A review keeps its place until it
 * finishes, so ending it also stops one already talking to the model.
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
        lateinit var job: Job
        // Started only once it's in the map, so it always finds itself there when it ends.
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(quietMillis)
                run(chatId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Nobody is watching: a review that fails is no review this time, never a crash of the app in the background.
            } finally {
                if (pending[chatId] === job) pending.remove(chatId)
            }
        }
        pending[chatId] = job
        job.start()
    }

    /** The user is back in [chatId], or deleted it: no review waits or runs for it. */
    fun returned(chatId: String) {
        pending.remove(chatId)?.cancel()
    }

    /** No review waits or runs for any chat, as when every chat is deleted. */
    fun cancelAll() {
        pending.values.forEach(Job::cancel)
        pending.clear()
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
