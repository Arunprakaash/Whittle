package app.whittle.automations

import app.whittle.agent.AgentMessages
import app.whittle.config.defaults.BundledConfigSource
import app.whittle.core.common.EpochClock
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigFiles
import app.whittle.core.config.ConfigLoader
import app.whittle.core.model.Provenance
import app.whittle.core.common.IdGenerator
import app.whittle.tools.api.ToolId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationRunnerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val due = LocalDateTime.parse("2026-10-22T07:00").atZone(zone).toInstant().toEpochMilli()
    private var now = due
    private val backend = MemoryBackend()
    private val alarms = RecordingAlarms()
    private val chat = FakeChat()
    private var inForeground = false
    private var protections = 0
    private lateinit var gate: EventGate
    private val messages = assertIs<Outcome.Success<AgentMessages>>(
        ConfigLoader(BundledConfigSource()).load(ConfigFiles.agentMessages, AgentMessages.serializer(), AgentMessages.SCHEMA_VERSION),
    ).value

    private suspend fun Automations.savedEvent(): Automation {
        val draft = draft("Bank", "Log the spend").copy(schedule = null, trigger = Trigger.Sms(senders = listOf("HDFCBK")))
        val result = assertIs<SaveResult.Saved>(save(draft))
        alarms.set.clear()
        return result.automation
    }

    private fun sms(text: String, at: Long = now) = PhoneEvent(EventKind.SMS, "HDFCBK", text, at)

    private fun TestScope.setUp(): Pair<Automations, AutomationRunner> {
        val automations = Automations(backend, alarms, testAutomationsConfig, EpochClock { now }, IdGenerator { "brief" }, { zone }, this)
        val gate = EventGate(MemoryEventBackend(), testEventsConfig, CodeMask(testCodeMaskConfig), EpochClock { now }, { zone }, this)
        this@AutomationRunnerTest.gate = gate
        val runner = AutomationRunner(
            automations, chat, EpochClock { now }, { inForeground }, { protections++ },
            gate, messages, maxPastedChars = 8_000, zones = { zone }, main = EmptyCoroutineContext,
        )
        return automations to runner
    }

    private suspend fun Automations.saved(): Automation {
        val result = assertIs<SaveResult.Saved>(save(draft("Brief", "Brief me")))
        alarms.set.clear()
        return result.automation
    }

    @Test
    fun `a due automation starts in its own chat, is recorded and protected`() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.saved()
        runner.due(automation.id, due)
        val (started, tools) = chat.started.single()
        assertEquals("Brief me", started.prompt)
        assertEquals(setOf(ToolId("web_search"), ToolId("web_fetch"), ToolId("update_plan")), tools)
        assertEquals(due, automations.all.value.single().lastRunAtMillis)
        assertEquals("chat-1", automations.all.value.single().lastChatId)
        assertEquals(1, protections)
    }

    @Test
    fun `the next run is set even when this one can't start`() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.saved()
        chat.isRunning = true
        runner.due(automation.id, due)
        assertTrue(alarms.set.any { !it.retry && it.atMillis > due }, "the next day's alarm must be set")
    }

    private suspend fun Automations.savedEvery(minutes: Int): Automation {
        val result = assertIs<SaveResult.Saved>(save(draft("Poll", "Check").copy(schedule = null, trigger = Trigger.Every(minutes))))
        alarms.set.clear()
        return result.automation
    }

    @Test
    fun `an interval that can't start keeps its next run a slot later, retries, then notes the miss`() = runTest {
        val (automations, runner) = setUp()
        val every = automations.savedEvery(30)
        chat.hasModel = false
        val slot = 30 * 60_000L
        runner.due(every.id, due)
        assertEquals(due + slot, alarms.set.single { !it.retry }.atMillis)
        assertEquals(Alarm(every.id, now + testAutomationsConfig.retryAfterMillis, due, retry = true), alarms.set.single { it.retry })
        alarms.set.clear()
        now = due + testAutomationsConfig.retryAfterMillis
        runner.due(every.id, due)
        assertEquals(due + slot, alarms.set.single { !it.retry }.atMillis)
        alarms.set.clear()
        now = due + testAutomationsConfig.giveUpAfterMillis
        runner.due(every.id, due)
        assertTrue(alarms.set.none { it.retry })
        assertEquals(due, automations.all.value.single().missedAtMillis)
    }

    @Test
    fun `an interval that starts sets one next alarm an interval after the run`() = runTest {
        val (automations, runner) = setUp()
        val every = automations.savedEvery(30)
        runner.due(every.id, due)
        assertEquals(1, chat.started.size)
        assertEquals(due + 30 * 60_000L, alarms.set.single().atMillis)
        assertTrue(alarms.set.none { it.retry })
    }

    @Test
    fun `waits while the user is in the app, a task runs, the chats are loading or no model is connected`() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.saved()
        val busy = listOf<() -> Unit>(
            { inForeground = true },
            { chat.isRunning = true },
            { chat.restored = false },
            { chat.hasModel = false },
        )
        busy.forEach { makeBusy ->
            inForeground = false
            chat.isRunning = false
            chat.restored = true
            chat.hasModel = true
            makeBusy()
            alarms.set.clear()
            runner.due(automation.id, due)
            assertTrue(chat.started.isEmpty())
            assertEquals(Alarm(automation.id, now + testAutomationsConfig.retryAfterMillis, due, retry = true), alarms.set.single { it.retry })
        }
        assertEquals(0, protections)
        assertNull(automations.all.value.single().missedAtMillis)
    }

    @Test
    fun `gives up once it's too late and notes the missed run`() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.saved()
        chat.hasModel = false
        now = due + testAutomationsConfig.giveUpAfterMillis
        runner.due(automation.id, due)
        assertTrue(alarms.set.none { it.retry })
        assertEquals(due, automations.all.value.single().missedAtMillis)
    }

    @Test
    fun `a due time that already ran doesn't run again`() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.saved()
        runner.due(automation.id, due)
        // The retry alarm for the same due time goes off too.
        now = due + testAutomationsConfig.retryAfterMillis
        runner.due(automation.id, due)
        assertEquals(1, chat.started.size)
    }

    @Test
    fun `an automation that was turned off or deleted does nothing`() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.saved()
        automations.setEnabled(automation.id, false)
        alarms.set.clear()
        runner.due(automation.id, due)
        runner.due("gone", due)
        assertTrue(chat.started.isEmpty())
        assertTrue(alarms.set.isEmpty())
    }

    @Test
    fun `Run now starts at once, even with the user in the app`() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.saved()
        inForeground = true
        assertEquals(RunNowResult.STARTED, runner.runNow(automation.id))
        assertEquals(1, chat.started.size)
        assertEquals(0, protections, "the user is looking; the app's own rule decides about the service")
    }

    @Test
    fun `Run now says why it couldn't start`() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.saved()
        assertEquals(RunNowResult.NOT_FOUND, runner.runNow("gone"))
        chat.hasModel = false
        assertEquals(RunNowResult.NO_MODEL, runner.runNow(automation.id))
        chat.hasModel = true
        chat.isRunning = true
        assertEquals(RunNowResult.BUSY, runner.runNow(automation.id))
        assertTrue(chat.started.isEmpty())
    }

    @Test
    fun eventRunCarriesEventsAsUntrustedPaste() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.savedEvent()
        gate.offer(automation.id, sms("Spent 250 at Cafe"))
        runner.due(automation.id, now)
        assertEquals("Log the spend", chat.started.single().first.prompt)
        val block = chat.pasted.single().single()
        assertIs<Provenance.Untrusted>(block.provenance)
        assertTrue("Text from HDFCBK at 07:00: Spent 250 at Cafe" in block.text, block.text)
        assertEquals(1, protections)
        assertTrue(gate.pending().isEmpty())
    }

    private suspend fun Automations.savedWith(trigger: Trigger): Automation {
        val result = assertIs<SaveResult.Saved>(save(draft("Watch", "React").copy(schedule = null, trigger = trigger)))
        alarms.set.clear()
        return result.automation
    }

    private suspend fun blockText(runner: AutomationRunner, id: String): String {
        runner.due(id, now)
        return chat.pasted.single().single().text
    }

    @Test
    fun aChargingEventSaysPluggedInWithoutGapsOrAColon() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.savedWith(Trigger.Charging(Edge.START))
        gate.offer(automation.id, PhoneEvent(EventKind.CHARGING, "", "", now, Edge.START))
        val text = blockText(runner, automation.id)
        assertTrue("\nPlugged in at 07:00\n" in text, text)
        assertTrue("from the phone" in text, text)
    }

    @Test
    fun aHiddenWifiNameIsSaidAndTheEdgeIsStated() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.savedWith(Trigger.Wifi(null, Edge.END))
        gate.offer(automation.id, PhoneEvent(EventKind.WIFI, "", "", now, Edge.END))
        val text = blockText(runner, automation.id)
        assertTrue("\nLeft Wi-Fi network (name hidden by Android) at 07:00\n" in text, text)
    }

    @Test
    fun aPlaceEventNamesTheEdgeAndThePlace() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.savedWith(Trigger.Place("Home", 12.0, 77.0, 200, Edge.START))
        gate.offer(automation.id, PhoneEvent(EventKind.PLACE, "Home", "", now, Edge.START))
        val text = blockText(runner, automation.id)
        assertTrue("\nArrived at Home at 07:00\n" in text, text)
        assertTrue("from Home" in text, text)
    }

    @Test
    fun aNotificationShowsTheAppsNameNotItsPackage() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.savedWith(Trigger.Notification("com.whatsapp", "WhatsApp"))
        gate.offer(automation.id, PhoneEvent(EventKind.NOTIFICATION, "com.whatsapp", "Hi", now, sourceLabel = "WhatsApp"))
        val text = blockText(runner, automation.id)
        assertTrue("Notification from WhatsApp at 07:00: Hi" in text, text)
        assertFalse("com.whatsapp" in text, text)
    }

    @Test
    fun eventRunThatCantStartPutsEventsBack() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.savedEvent()
        gate.offer(automation.id, sms("one"))
        chat.isRunning = true
        runner.due(automation.id, now)
        assertTrue(chat.started.isEmpty())
        assertEquals(Alarm(automation.id, now + testAutomationsConfig.retryAfterMillis, now, retry = true), alarms.set.single { it.retry })
        assertEquals(listOf("one"), gate.take(automation.id).map { it.text })
    }

    @Test
    fun emptyQueueStartsNothing() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.savedEvent()
        runner.due(automation.id, now)
        assertTrue(chat.started.isEmpty())
        assertEquals(0, protections)
    }

    @Test
    fun eventsArrivingNowRunNowAndLaterOnesSetTheDrain() = runTest {
        val (automations, runner) = setUp()
        val automation = automations.savedEvent()
        gate.offer(automation.id, sms("first"))
        runner.eventsArrived(automation.id)
        assertEquals(1, chat.started.size)
        gate.offer(automation.id, sms("second"))
        runner.eventsArrived(automation.id)
        assertEquals(1, chat.started.size)
        val at = now + testEventsConfig.cooldownMillis
        assertEquals(Alarm(automation.id, at, at, retry = true), alarms.set.last())
    }
}
