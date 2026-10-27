package app.whittle.chat

import app.whittle.llm.api.ModelDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class QuietReviewTest {
    private val ran = mutableListOf<String>()

    private fun TestScope.reviews() = QuietReviews(backgroundScope, quietMillis = 1_000) { ran += it }

    @Test
    fun `a chat is reviewed once it has been quiet long enough`() = runTest {
        reviews().left("a")
        advanceTimeBy(999)
        runCurrent()
        assertTrue(ran.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("a"), ran)
    }

    @Test
    fun `a review that throws is dropped and the next leave still reviews`() = runTest {
        val reviews = QuietReviews(backgroundScope, quietMillis = 1_000) { id ->
            ran += id
            if (id == "bad") error("no network stack")
        }
        reviews.left("bad")
        advanceTimeBy(1_000)
        runCurrent()
        reviews.left("good")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("bad", "good"), ran)
    }

    @Test
    fun `cancelling all leaves no review waiting`() = runTest {
        val reviews = reviews()
        reviews.left("a")
        reviews.left("b")
        advanceTimeBy(500)
        reviews.cancelAll()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(ran.isEmpty())
        reviews.left("c")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("c"), ran)
    }

    @Test
    fun `coming back cancels the review`() = runTest {
        val reviews = reviews()
        reviews.left("a")
        advanceTimeBy(500)
        reviews.returned("a")
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `deleting a chat stops a review already talking to the model`() = runTest {
        val answered = mutableListOf<String>()
        val reviews = QuietReviews(backgroundScope, quietMillis = 1_000) { id ->
            ran += id
            delay(5_000) // The model call.
            answered += id
        }
        reviews.left("a")
        reviews.left("b")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("a", "b"), ran)

        reviews.returned("a") // As deleting the chat does.
        reviews.cancelAll() // As deleting every chat does.
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(answered.isEmpty())
    }

    @Test
    fun `a finished review lets the chat be reviewed again after the next leave`() = runTest {
        val reviews = reviews()
        reviews.left("a")
        advanceTimeBy(1_000)
        runCurrent()
        reviews.returned("a") // Nothing left to cancel.
        reviews.left("a")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("a", "a"), ran)
    }

    @Test
    fun `leaving twice reviews once, a full wait after the last time`() = runTest {
        val reviews = reviews()
        reviews.left("a")
        advanceTimeBy(800)
        reviews.left("a")
        advanceTimeBy(800)
        runCurrent()
        assertTrue(ran.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertEquals(listOf("a"), ran)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("a"), ran)
    }

    @Test
    fun `returning to one chat leaves another's review alone`() = runTest {
        val reviews = reviews()
        reviews.left("a")
        reviews.left("b")
        reviews.returned("a")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("b"), ran)
    }

    @Test
    fun `an automation's chat is never handed to the review as a user's`() = runTest {
        val calls = mutableListOf<Boolean>()
        val review: suspend (String, List<ThreadItem>, ModelDescriptor?, Boolean) -> Unit = { _, _, _, automation -> calls += automation }
        reviewChat("a", emptyList(), model = null, automationName = "Morning news", review = review)
        reviewChat("b", emptyList(), model = null, automationName = null, review = review)
        assertEquals(listOf(true, false), calls)
    }

    @Test
    fun `a task that ends after the user left the app starts the wait, one they are watching does not`() {
        assertTrue(goesQuietWhenIdle(working = false, onScreen = true, appVisible = false))
        assertFalse(goesQuietWhenIdle(working = false, onScreen = true, appVisible = true))
        assertTrue(goesQuietWhenIdle(working = false, onScreen = false, appVisible = true))
        assertFalse(goesQuietWhenIdle(working = true, onScreen = false, appVisible = false))
    }
}
