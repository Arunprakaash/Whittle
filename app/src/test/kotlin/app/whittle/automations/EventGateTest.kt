package app.whittle.automations

import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventGateTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.parse("2026-10-22T06:00").atZone(zone).toInstant().toEpochMilli()
    private val backend = MemoryEventBackend()
    private val config = testEventsConfig

    private fun TestScope.gate(events: EventsConfig = config) =
        EventGate(backend, events, CodeMask(events.codeMask), EpochClock { now }, { zone }, this)

    private fun event(text: String, at: Long = now) = PhoneEvent(EventKind.SMS, "HDFCBK", text, at)

    @Test
    fun firstEventRunsNow() = runTest {
        assertEquals(GateDecision.RunAt(now), gate().offer("a", event("Spent 10")))
    }

    @Test
    fun eventsDuringCooldownWaitAndBatch() = runTest {
        val gate = gate()
        gate.offer("a", event("first"))
        gate.take("a")
        val last = now
        val decisions = listOf("one", "two", "three").map {
            now += 20_000
            gate.offer("a", event(it))
        }
        assertEquals(List(3) { GateDecision.RunAt(last + 120_000) }, decisions)
        assertEquals(mapOf("a" to last + 120_000), gate.pending())
        assertEquals(listOf("one", "two", "three"), gate.take("a").map { it.text })
        assertTrue(gate.take("a").isEmpty())
        assertTrue(gate.pending().isEmpty())
    }

    @Test
    fun queueCapDropsOldest() = runTest {
        val gate = gate(config.copy(maxQueued = 3))
        (1..5).forEach { gate.offer("a", event("e$it")) }
        assertEquals(listOf("e3", "e4", "e5"), gate.take("a").map { it.text })
    }

    @Test
    fun dailyCapDropsAndClearsQueue() = runTest {
        val gate = gate(config.copy(maxRunsPerDay = 2))
        repeat(2) {
            gate.offer("a", event("run"))
            gate.take("a")
            now += 120_000
        }
        assertEquals(GateDecision.Dropped, gate.offer("a", event("over")))
        assertEquals(GateDecision.Dropped, gate.offer("a", event("over again")))
        assertTrue(gate.take("a").isEmpty())
        assertTrue(gate.pending().isEmpty())
        assertEquals(2, gate.dropped.value["a"])
    }

    @Test
    fun capResetsNextDay() = runTest {
        val gate = gate(config.copy(maxRunsPerDay = 1))
        gate.offer("a", event("run"))
        gate.take("a")
        assertEquals(GateDecision.Dropped, gate.offer("a", event("over")))
        assertEquals(1, gate.dropped.value["a"])
        now += 24 * 3_600_000L
        assertTrue(gate.offer("a", event("next day")) is GateDecision.RunAt)
        assertEquals(0, gate.dropped.value["a"] ?: 0)
    }

    @Test
    fun textIsMaskedBeforeStoring() = runTest {
        val gate = gate()
        gate.offer("a", event("Your OTP is 482913. Do not share."))
        assertFalse(backend.stored.toString().contains("482913"))
        assertTrue(gate.take("a").single().text.contains("[code hidden]"))
    }

    @Test
    fun textIsCutBeforeMasking() = runTest {
        val gate = gate(config.copy(maxEventChars = 10))
        gate.offer("a", event("x".repeat(50)))
        assertEquals(10, gate.take("a").single().text.length)
    }

    @Test
    fun putBackRequeuesAtFrontAndUndoesTheRunCount() = runTest {
        val gate = gate(config.copy(maxRunsPerDay = 2))
        gate.offer("a", event("one"))
        val taken = gate.take("a")
        gate.offer("a", event("two"))
        gate.putBack("a", taken)
        assertEquals(listOf("one", "two"), gate.take("a").map { it.text })
        // Two takes, one put back: one run counted, so one more is still allowed today.
        assertTrue(gate.offer("a", event("three")) is GateDecision.RunAt)
        gate.take("a")
        assertEquals(GateDecision.Dropped, gate.offer("a", event("four")))
    }

    @Test
    fun queuesSurviveAFreshGate() = runTest {
        gate().offer("a", event("kept"))
        assertEquals(listOf("kept"), gate().take("a").map { it.text })
    }

    @Test
    fun dropsLoadedFromDiskShowTodayOnly() = runTest {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        backend.stored = listOf(
            QueuedEvents("a", emptyList(), null, today, 3, 4),
            QueuedEvents("b", emptyList(), null, "2020-01-01", 3, 9),
        )
        val gate = gate()
        gate.pending()
        assertEquals(mapOf("a" to 4), gate.dropped.value)
        now += 24 * 3_600_000L
        gate.offer("c", event("next day"))
        assertTrue(gate.dropped.value.isEmpty())
    }

    @Test
    fun aFailedWriteIsReported() = runTest {
        val failing = object : EventQueueBackend {
            override suspend fun load() = emptyList<QueuedEvents>()

            override suspend fun save(all: List<QueuedEvents>): Outcome<Unit> =
                Outcome.Failure(HarnessError.Storage("disk full"))
        }
        val gate = EventGate(failing, config, CodeMask(config.codeMask), EpochClock { now }, { zone }, this)
        assertEquals(null, gate.saveError.value)
        gate.offer("a", event("x"))
        assertTrue(gate.saveError.value != null)
    }
}
