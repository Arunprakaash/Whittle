package app.whittle.automations.events

import app.whittle.automations.Automation
import app.whittle.automations.Automations
import app.whittle.automations.EventGate
import app.whittle.automations.EventKind
import app.whittle.automations.CodeMask
import app.whittle.automations.MemoryBackend
import app.whittle.automations.MemoryEventBackend
import app.whittle.automations.PhoneEvent
import app.whittle.automations.RecordingAlarms
import app.whittle.automations.Trigger
import app.whittle.automations.testAutomationsConfig
import app.whittle.automations.testCodeMaskConfig
import app.whittle.automations.testEventsConfig
import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class EventDispatchTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = 1_800_000_000_000L

    @Test
    fun dispatchOffersOnlyToMatchingEnabledEventAutomations() = runTest {
        val automations = Automations(
            MemoryBackend(), RecordingAlarms(), testAutomationsConfig.copy(maxAutomations = 10),
            EpochClock { now }, IdGenerator { "x" }, { zone }, this,
        )
        suspend fun add(id: String, trigger: Trigger, enabled: Boolean = true) {
            val a = automations.draft(id, "Do it").copy(id = id, schedule = null, trigger = trigger, enabled = enabled)
            automations.save(a)
        }
        add("bank", Trigger.Sms(listOf("HDFCBK")))
        add("other", Trigger.Sms(listOf("AMZN")))
        add("off", Trigger.Sms(listOf("HDFCBK")), enabled = false)
        add("poll", Trigger.Every(30))
        val gate = EventGate(MemoryEventBackend(), testEventsConfig, CodeMask(testCodeMaskConfig), EpochClock { now }, { zone }, this)
        val arrived = mutableListOf<String>()
        val dispatch = EventDispatch(automations, gate, { true }) { arrived += it }

        dispatch.dispatch(PhoneEvent(EventKind.SMS, "HDFCBK", "Spent 10", now))

        assertEquals(listOf("bank"), arrived)
        assertEquals(setOf("bank"), gate.pending().keys)
    }

    @Test
    fun aNotificationReachesTheQueueWithTheAppsName() = runTest {
        val automations = Automations(
            MemoryBackend(), RecordingAlarms(), testAutomationsConfig, EpochClock { now }, IdGenerator { "x" }, { zone }, this,
        )
        automations.save(automations.draft("chat", "Do it").copy(id = "chat", schedule = null, trigger = Trigger.Notification("com.whatsapp", "WhatsApp")))
        val gate = EventGate(MemoryEventBackend(), testEventsConfig, CodeMask(testCodeMaskConfig), EpochClock { now }, { zone }, this)
        EventDispatch(automations, gate, { true }) {}.dispatch(PhoneEvent(EventKind.NOTIFICATION, "com.whatsapp", "Hi", now))
        val queued = gate.take("chat").single()
        assertEquals("WhatsApp", queued.sourceLabel)
        assertEquals("com.whatsapp", queued.source)
    }

    @Test
    fun dispatchWaitsForAutomationsStillLoading() = runTest {
        val saved = Automations(
            MemoryBackend(), RecordingAlarms(), testAutomationsConfig, EpochClock { now }, IdGenerator { "x" }, { zone }, this,
        ).draft("bank", "Do it").copy(id = "bank", schedule = null, trigger = Trigger.Sms(listOf("HDFCBK")))
        val slow = object : app.whittle.automations.AutomationBackend {
            override suspend fun load(): List<Automation> {
                kotlinx.coroutines.delay(5_000)
                return listOf(saved)
            }

            override suspend fun save(automations: List<Automation>) = app.whittle.core.common.Outcome.Success(Unit)
        }
        val automations = Automations(slow, RecordingAlarms(), testAutomationsConfig, EpochClock { now }, IdGenerator { "x" }, { zone }, this)
        val gate = EventGate(MemoryEventBackend(), testEventsConfig, CodeMask(testCodeMaskConfig), EpochClock { now }, { zone }, this)
        val arrived = mutableListOf<String>()
        EventDispatch(automations, gate, { true }) { arrived += it }.dispatch(PhoneEvent(EventKind.SMS, "HDFCBK", "Spent 10", now))
        assertEquals(listOf("bank"), arrived)
    }
}
