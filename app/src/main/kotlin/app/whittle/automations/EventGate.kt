package app.whittle.automations

import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * What waits for one automation: the [events] not yet run, when it last ran, and today's counts. [day] is the phone's
 * local date the counts belong to; they start over on another day.
 */
@Serializable
data class QueuedEvents(
    val automationId: String,
    val events: List<PhoneEvent>,
    val lastRunAtMillis: Long?,
    val day: String,
    val runsToday: Int,
    val droppedToday: Int,
)

/** Where the queues are kept between launches. */
interface EventQueueBackend {
    suspend fun load(): List<QueuedEvents>

    suspend fun save(all: List<QueuedEvents>): Outcome<Unit>
}

/** What became of an event offered to the gate. */
sealed interface GateDecision {
    /** Kept; its automation may run at [atMillis]. */
    data class RunAt(val atMillis: Long) : GateDecision

    /** Not kept: the automation has run as often as it may today. */
    data object Dropped : GateDecision

    /** Not kept, and not counted: it seems to hold a one-time code, which no run may see. */
    data object HeldBack : GateDecision
}

/**
 * The queue between an event arriving and its automation's run. An automation runs for events at most once per
 * cooldown and [EventsConfig.maxRunsPerDay] times a day; events in between wait and go to one run together. An event
 * that seems to hold a one-time code is never kept, so neither disk nor model sees one; any other is cut to length.
 */
class EventGate(
    private val backend: EventQueueBackend,
    private val config: EventsConfig,
    private val mask: CodeMask,
    private val clock: EpochClock,
    private val zones: () -> ZoneId,
    scope: CoroutineScope,
) {
    private val changes = Mutex()
    private val queues = LinkedHashMap<String, QueuedEvents>()
    private val loaded = scope.async {
        backend.load().forEach { queues[it.automationId] = it }
        publishDropped()
    }
    private val _dropped = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _saveError = MutableStateFlow<HarnessError?>(null)

    /** Events dropped today per automation, for the list row. */
    val dropped: StateFlow<Map<String, Int>> = _dropped.asStateFlow()

    /** Why the queue couldn't be written last time, or null. Events still run from memory, but a kill now would lose them. */
    val saveError: StateFlow<HarnessError?> = _saveError.asStateFlow()

    suspend fun offer(automationId: String, event: PhoneEvent): GateDecision = changes.withLock {
        // Judged with room past the cut, so a code just inside it still finds its keyword; the first cut bounds the work.
        val app = event.source.takeIf { event.kind == EventKind.NOTIFICATION }
        if (mask.looksLikeCode(event.text.take(config.maxEventChars + config.codeMask.nearChars), app)) return@withLock GateDecision.HeldBack
        val kept = event.copy(text = event.text.take(config.maxEventChars))
        val queue = today(automationId)
        val decision = if (queue.runsToday >= config.maxRunsPerDay) {
            put(queue.copy(events = emptyList(), droppedToday = queue.droppedToday + 1))
            GateDecision.Dropped
        } else {
            val all = queue.events + kept
            val over = (all.size - config.maxQueued).coerceAtLeast(0)
            val next = queue.copy(events = all.drop(over), droppedToday = queue.droppedToday + over)
            put(next)
            GateDecision.RunAt(runTime(next))
        }
        persist()
        decision
    }

    /** Empties [automationId]'s queue for a run and counts that run. */
    suspend fun take(automationId: String): List<PhoneEvent> = changes.withLock {
        val queue = today(automationId)
        if (queue.events.isEmpty()) return@withLock emptyList()
        put(queue.copy(events = emptyList(), lastRunAtMillis = clock.nowMillis(), runsToday = queue.runsToday + 1))
        persist()
        queue.events
    }

    /** Returns [events] a run couldn't start with to the front of the queue, and uncounts that run. */
    suspend fun putBack(automationId: String, events: List<PhoneEvent>) = changes.withLock {
        if (events.isEmpty()) return@withLock
        val queue = today(automationId)
        val all = events + queue.events
        val over = (all.size - config.maxQueued).coerceAtLeast(0)
        put(queue.copy(events = all.drop(over), runsToday = (queue.runsToday - 1).coerceAtLeast(0), droppedToday = queue.droppedToday + over))
        persist()
    }

    /** Drops everything kept for [automationId]: its waiting events and counts, for an automation that is deleted. */
    suspend fun forget(automationId: String) = changes.withLock {
        loaded.await()
        if (queues.remove(automationId) != null) {
            publishDropped()
            persist()
        }
    }

    /** When each automation with events waiting may run them. */
    suspend fun pending(): Map<String, Long> = changes.withLock {
        loaded.await()
        queues.values.filter { it.events.isNotEmpty() }.associate { it.automationId to runTime(it) }
    }

    private fun runTime(queue: QueuedEvents): Long = maxOf(clock.nowMillis(), queue.lastRunAtMillis?.plus(config.cooldownMillis) ?: 0)

    /** [automationId]'s queue with its counts started over if it is from another day. */
    private suspend fun today(automationId: String): QueuedEvents {
        loaded.await()
        publishDropped()
        val day = day()
        val queue = queues[automationId] ?: return QueuedEvents(automationId, emptyList(), null, day, 0, 0)
        return if (queue.day == day) queue else queue.copy(day = day, runsToday = 0, droppedToday = 0)
    }

    private fun put(queue: QueuedEvents) {
        queues[queue.automationId] = queue
        publishDropped()
    }

    private fun day(): String = Instant.ofEpochMilli(clock.nowMillis()).atZone(zones()).toLocalDate().toString()

    /** Today's drops only: counts from an earlier day are history. */
    private fun publishDropped() {
        val day = day()
        _dropped.value = queues.values.filter { it.day == day && it.droppedToday > 0 }.associate { it.automationId to it.droppedToday }
    }

    private suspend fun persist() {
        _saveError.value = (backend.save(queues.values.toList()) as? Outcome.Failure)?.error
    }
}
