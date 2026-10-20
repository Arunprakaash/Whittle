package app.whittle.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.whittle.AppGraph
import app.whittle.WhittleApplication
import app.whittle.chat.ChatDocument
import app.whittle.chat.ThreadItem
import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.Role
import app.whittle.storage.chats.ChatId
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Debug builds only: fills the chat list with sample chats in every section, to try the sidebar and Chats page. */
class SampleChatsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as WhittleApplication).graph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                SampleChats.seed(graph)
            } finally {
                pending.finish()
            }
        }
    }
}

private object SampleChats {
    private data class Sample(val title: String, val daysAgo: Long, val pinned: Boolean = false)

    private val SAMPLES = listOf(
        Sample("Plant reminder", daysAgo = 3, pinned = true),
        Sample("Weekly grocery list", daysAgo = 12, pinned = true),
        Sample("Mom's birthday ideas", daysAgo = 40, pinned = true),
    ) + listOf(
        "Running late for dinner", "Split the restaurant bill", "What time is it in Tokyo?", "Rewrite my message to the landlord",
        "Shopping list for pasta night", "Summarize this article", "Plan a 20-minute workout", "Convert 5 miles to km",
    ).map { Sample(it, daysAgo = 0) } + listOf(
        "Gift ideas under 2000 rupees", "Draft a leave email", "Best time to water plants", "Fix my CV summary",
        "Weekend trip to Coorg", "Translate this note to Hindi",
    ).map { Sample(it, daysAgo = 1) } + listOf(
        "Count lines in notes.txt", "Budget for October", "Recipe with paneer", "Reply to Priya's message", "Movie night picks",
        "Clean up my to-do list", "Explain my electricity bill", "Morning routine checklist", "Book club questions", "Compare two phone plans",
    ).mapIndexed { index, title -> Sample(title, daysAgo = 2L + index % 6) } + listOf(
        "Diwali shopping list", "Car service reminder", "Learn basic Spanish phrases", "Kids' homework help", "Travel packing list",
    ).mapIndexed { index, title -> Sample(title, daysAgo = 9L + index * 4) } + listOf(
        "Wedding speech draft", "Tax documents checklist", "Home Wi-Fi troubleshooting", "New year goals",
    ).mapIndexed { index, title -> Sample(title, daysAgo = 45L + index * 30) }

    private const val ANSWER = "This is a sample chat for trying the chat list."
    private const val MINUTE_MILLIS = 60_000L

    suspend fun seed(graph: AppGraph) {
        val zone = ZoneId.systemDefault()
        val now = graph.clock.nowMillis()
        val startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        // Today's samples sit between midnight and now, so none lands in the future or in yesterday.
        val todaySpacing = ((now - startOfToday) / (SAMPLES.count { it.daysAgo == 0L } + 1)).coerceAtMost(15 * MINUTE_MILLIS)
        var todayIndex = 0
        SAMPLES.forEachIndexed { index, sample ->
            val updatedAt = if (sample.daysAgo == 0L) {
                now - (++todayIndex) * todaySpacing
            } else {
                LocalDate.now(zone).minusDays(sample.daysAgo).atTime(9 + index % 10, (index * 7) % 60).atZone(zone).toInstant().toEpochMilli()
            }
            val id = ChatId("sample-${index + 1}")
            val document = ChatDocument(
                schemaVersion = ChatDocument.SCHEMA_VERSION,
                title = sample.title,
                items = listOf(ThreadItem.User("u", sample.title, steer = false), ThreadItem.Narration("a", ANSWER, streaming = false)),
                history = listOf(Message.userText(sample.title), Message(Role.ASSISTANT, listOf(ContentBlock.Text(ANSWER)))),
                activeRunId = null,
                nextKey = 2,
            )
            graph.chats.importChat(id, sample.title, document.encode(), Instant.ofEpochMilli(updatedAt).toEpochMilli())
            graph.chats.setPinned(id, sample.pinned)
        }
    }
}
