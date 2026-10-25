package app.whittle.chat

import app.whittle.llm.api.ModelDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
