package app.whittle.reminders

import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.IdGenerator
import app.whittle.core.common.Outcome
import app.whittle.storage.ListStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A reminder Whittle shows as a notification at [atMillis]; no model runs for it. */
@Serializable
data class Reminder(val id: String, val text: String, val atMillis: Long)

/** How adding a reminder went: [Invalid] carries why, in words for the model. */
sealed interface ReminderAdded {
    data class Added(val reminder: Reminder) : ReminderAdded

    data class Invalid(val reason: String) : ReminderAdded

    data class Failed(val error: HarnessError) : ReminderAdded
}

/** Wakes the app when a reminder is due. */
interface ReminderAlarms {
    fun set(id: String, atMillis: Long)

    fun cancel(id: String)
}

/**
 * Whittle's own reminders: kept encrypted on the phone, each with an exact alarm, shown by [show] when due. A reminder
 * that came due while the phone was off shows as soon as its alarms are set again.
 */
class Reminders(
    private val store: ListStore<Reminder>,
    private val alarms: ReminderAlarms,
    private val clock: EpochClock,
    private val ids: IdGenerator,
    private val maxReminders: Int,
    private val maxTextChars: Int,
    private val show: (Reminder) -> Unit,
) {
    private val lock = Mutex()
    private val mutableAll = MutableStateFlow<List<Reminder>?>(null)

    /** The reminders still to come, soonest first; null until loaded. */
    val all: StateFlow<List<Reminder>?> = mutableAll.asStateFlow()

    /** Adds a reminder of [text] at [atMillis], which must be in the future. */
    suspend fun add(text: String, atMillis: Long): ReminderAdded = lock.withLock {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > maxTextChars) return@withLock ReminderAdded.Invalid("text must be 1 to $maxTextChars characters")
        if (atMillis <= clock.nowMillis()) return@withLock ReminderAdded.Invalid("that time has passed")
        val current = loaded()
        if (current.size >= maxReminders) return@withLock ReminderAdded.Invalid("there are already $maxReminders reminders waiting; cancel some first")
        val reminder = Reminder(ids.newId(), trimmed, atMillis)
        save(current + reminder)?.let { return@withLock ReminderAdded.Failed(it.error) }
        alarms.set(reminder.id, atMillis)
        ReminderAdded.Added(reminder)
    }

    /** Cancels the reminder [id]; false when there's none. */
    suspend fun cancel(id: String): Outcome<Boolean> = lock.withLock {
        val current = loaded()
        if (current.none { it.id == id }) return@withLock Outcome.Success(false)
        save(current.filterNot { it.id == id })?.let { return@withLock it }
        alarms.cancel(id)
        Outcome.Success(true)
    }

    /** The reminders still to come, soonest first. */
    suspend fun list(): List<Reminder> = lock.withLock { loaded() }

    /** The alarm for [id] went off: shows it and forgets it. */
    suspend fun due(id: String) = lock.withLock {
        val current = loaded()
        val reminder = current.firstOrNull { it.id == id } ?: return@withLock
        show(reminder)
        save(current - reminder)
    }

    /** After a restart or a clock change: shows what came due meanwhile and sets every other alarm again. */
    suspend fun rescheduleAll() = lock.withLock {
        val current = loaded()
        val (late, waiting) = current.partition { it.atMillis <= clock.nowMillis() }
        late.forEach(show)
        if (late.isNotEmpty()) save(waiting)
        waiting.forEach { alarms.set(it.id, it.atMillis) }
    }

    private suspend fun loaded(): List<Reminder> = mutableAll.value ?: store.load().sortedBy { it.atMillis }.also { mutableAll.value = it }

    /** Null once saved; the failure otherwise, leaving what's kept as it was. */
    private suspend fun save(reminders: List<Reminder>): Outcome.Failure? {
        val sorted = reminders.sortedBy { it.atMillis }
        return when (val saved = store.save(sorted)) {
            is Outcome.Failure -> saved
            is Outcome.Success -> {
                mutableAll.value = sorted
                null
            }
        }
    }
}
