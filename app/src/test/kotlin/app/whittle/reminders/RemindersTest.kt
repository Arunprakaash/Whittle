package app.whittle.reminders

import app.whittle.core.common.EpochClock
import app.whittle.core.common.Outcome
import app.whittle.storage.ListStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RemindersTest {
    private var now = 1_000L
    private val saved = mutableListOf<Reminder>()
    private val alarms = mutableMapOf<String, Long>()
    private val shown = mutableListOf<String>()
    private var nextId = 0

    private val store = object : ListStore<Reminder> {
        override suspend fun load(): List<Reminder> = saved.toList()

        override suspend fun save(items: List<Reminder>): Outcome<Unit> {
            saved.clear()
            saved += items
            return Outcome.Success(Unit)
        }
    }

    private fun reminders(max: Int = 3) = Reminders(
        store = store,
        alarms = object : ReminderAlarms {
            override fun set(id: String, atMillis: Long) { alarms[id] = atMillis }

            override fun cancel(id: String) { alarms.remove(id) }
        },
        clock = EpochClock { now },
        ids = { "r${nextId++}" },
        maxReminders = max,
        maxTextChars = 20,
        show = { shown += it.text },
    )

    @Test
    fun `a reminder is kept with its alarm, shows when due, and is then forgotten`() = runTest {
        val reminders = reminders()
        val added = assertIs<ReminderAdded.Added>(reminders.add("  Call the bank ", 5_000)).reminder
        assertEquals("Call the bank", added.text)
        assertEquals(5_000L, alarms[added.id])
        reminders.due(added.id)
        assertEquals(listOf("Call the bank"), shown)
        assertTrue(reminders.list().isEmpty() && saved.isEmpty())
    }

    @Test
    fun `past times, empty or long text and too many waiting are refused`() = runTest {
        val reminders = reminders(max = 1)
        assertIs<ReminderAdded.Invalid>(reminders.add("Late", 500))
        assertIs<ReminderAdded.Invalid>(reminders.add(" ", 5_000))
        assertIs<ReminderAdded.Invalid>(reminders.add("x".repeat(21), 5_000))
        assertIs<ReminderAdded.Added>(reminders.add("One", 5_000))
        assertIs<ReminderAdded.Invalid>(reminders.add("Two", 6_000))
    }

    @Test
    fun `cancelling removes the reminder and its alarm, and says when there was none`() = runTest {
        val reminders = reminders()
        val added = assertIs<ReminderAdded.Added>(reminders.add("Stretch", 5_000)).reminder
        assertEquals(Outcome.Success(true), reminders.cancel(added.id))
        assertTrue(added.id !in alarms && saved.isEmpty())
        assertEquals(Outcome.Success(false), reminders.cancel(added.id))
    }

    @Test
    fun `after a restart, what came due meanwhile shows and the rest get their alarms again`() = runTest {
        saved += Reminder("a", "Missed", 2_000)
        saved += Reminder("b", "Later", 9_000)
        now = 3_000
        reminders().rescheduleAll()
        assertEquals(listOf("Missed"), shown)
        assertEquals(mapOf("b" to 9_000L), alarms)
        assertEquals(listOf("b"), saved.map { it.id })
    }
}
