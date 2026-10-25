package app.whittle.automations

import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import app.whittle.tools.api.ToolId
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.parse("2026-10-22T06:00").atZone(zone).toInstant().toEpochMilli()
    private val backend = MemoryBackend()
    private val alarms = RecordingAlarms()
    private val config = testAutomationsConfig
    private var nextId = 0

    private fun TestScope.automations() =
        Automations(backend, alarms, config, EpochClock { now }, IdGenerator { "a${nextId++}" }, { zone }, this)

    @Test
    fun `a new draft gets the default time, every day and the default tools`() = runTest {
        val draft = automations().draft("Brief", "Brief me")
        assertEquals(Schedule(420, Schedule.EVERY_DAY), draft.schedule)
        assertEquals(setOf("web_search", "web_fetch"), draft.tools)
    }

    @Test
    fun everyRunsMinutesAfterLastRun() = runTest {
        val automations = automations()
        val every = Automation("e", "Poll", "Check", schedule = null, trigger = Trigger.Every(30), tools = emptySet())
        assertEquals(now + 30 * 60_000, automations.nextRunMillis(every))
        assertEquals(now + 10 * 60_000, automations.nextRunMillis(every.copy(lastRunAtMillis = now - 20 * 60_000)))
        // One behind (blocked, say) shows the next slot after now, not now: slots at last run + 30, 60, 90, 120 min.
        assertEquals(now + 30 * 60_000, automations.nextRunMillis(every.copy(lastRunAtMillis = now - 90 * 60_000)))
        assertEquals(now + 10 * 60_000, automations.nextRunMillis(every.copy(lastRunAtMillis = now - 80 * 60_000)))
    }

    @Test
    fun everyBelowFloorRejected() = runTest {
        val automations = automations()
        val every = automations.draft("Poll", "Check").copy(schedule = null, trigger = Trigger.Every(10))
        assertEquals(AutomationProblem.INTERVAL_TOO_SHORT, refusal(automations.save(every)))
        assertIs<SaveResult.Saved>(automations.save(every.copy(trigger = Trigger.Every(15))))
    }

    private fun TestScope.automations(pending: Map<String, Long>) =
        Automations(backend, alarms, config, EpochClock { now }, IdGenerator { "a${nextId++}" }, { zone }, this, { pending })

    @Test
    fun rescheduleAllSetsDrainAlarmForQueuedEvents() = runTest {
        val automations = automations(mapOf("sms" to now + 90_000, "gone" to now + 1_000))
        val sms = automations.draft("Bank", "Log it").copy(id = "sms", schedule = null, trigger = Trigger.Sms(listOf("HDFCBK")))
        automations.save(sms)
        automations.rescheduleAll()
        assertEquals(listOf(Alarm("sms", now + 90_000, now + 90_000, retry = true)), alarms.set)
        // Saving an event automation never cancels its drain alarm.
        assertTrue(alarms.cancelled.isEmpty())
        assertEquals(listOf("sms", "sms"), alarms.nextCancelled)
    }

    @Test
    fun disablingAnEventAutomationCancelsEverything() = runTest {
        val automations = automations()
        val sms = automations.draft("Bank", "Log it").copy(id = "sms", schedule = null, trigger = Trigger.Sms())
        automations.save(sms)
        automations.setEnabled("sms", false)
        assertEquals(listOf("sms"), alarms.cancelled)
    }

    @Test
    fun eventAutomationSetsNoAlarm() = runTest {
        val automations = automations()
        val event = automations.draft("Bank", "Log it").copy(schedule = null, trigger = Trigger.Sms(listOf("HDFCBK")))
        assertTrue(event.isEvent)
        val saved = assertIs<SaveResult.Saved>(automations.save(event))
        assertNull(saved.nextRunMillis)
        assertTrue(alarms.set.isEmpty())
    }

    @Test
    fun anAutomationHasATimeOrATriggerNotBoth() {
        assertFailsWith<IllegalArgumentException> { Automation("a", "n", "p", schedule = null, tools = emptySet()) }
        assertFailsWith<IllegalArgumentException> {
            Automation("a", "n", "p", Schedule(420, Schedule.EVERY_DAY), trigger = Trigger.Charging(Edge.START), tools = emptySet())
        }
    }

    @Test
    fun `saving keeps it and sets its alarm for the next run`() = runTest {
        val automations = automations()
        val saved = assertIs<SaveResult.Saved>(automations.save(automations.draft(" Brief ", " Brief me ")))
        val seven = LocalDateTime.parse("2026-10-22T07:00").atZone(zone).toInstant().toEpochMilli()
        assertEquals(seven, saved.nextRunMillis)
        assertEquals("Brief", saved.automation.name)
        assertEquals(listOf(saved.automation), backend.stored)
        assertEquals(Alarm(saved.automation.id, seven, seven, retry = false), alarms.set.single())
    }

    @Test
    fun `saving an existing one replaces it`() = runTest {
        val automations = automations()
        val first = assertIs<SaveResult.Saved>(automations.save(automations.draft("Brief", "Brief me"))).automation
        automations.save(first.copy(name = "Morning"))
        assertEquals(listOf("Morning"), automations.all.value.map { it.name })
    }

    @Test
    fun `an edit keeps when it last ran, even if the editor opened before that run`() = runTest {
        val automations = automations()
        val opened = assertIs<SaveResult.Saved>(automations.save(automations.draft("Brief", "Brief me"))).automation
        automations.recordRun(opened.id, chatId = "chat", atMillis = now)
        val saved = assertIs<SaveResult.Saved>(automations.save(opened.copy(name = "Morning"))).automation
        assertEquals(now, saved.lastRunAtMillis)
        assertEquals("chat", automations.all.value.single().lastChatId)
    }

    @Test
    fun `turning one off cancels its alarm and turning it on sets it again`() = runTest {
        val automations = automations()
        val saved = assertIs<SaveResult.Saved>(automations.save(automations.draft("Brief", "Brief me"))).automation
        automations.setEnabled(saved.id, false)
        assertEquals(listOf(saved.id), alarms.cancelled)
        assertNull(automations.nextRunMillis(automations.all.value.single()))
        automations.setEnabled(saved.id, true)
        assertEquals(2, alarms.set.size)
    }

    @Test
    fun `deleting removes it and cancels its alarms`() = runTest {
        val automations = automations()
        val saved = assertIs<SaveResult.Saved>(automations.save(automations.draft("Brief", "Brief me"))).automation
        automations.delete(saved.id)
        assertTrue(backend.stored.isEmpty())
        assertEquals(listOf(saved.id), alarms.cancelled)
    }

    @Test
    fun `deleting also drops the events queued for it`() = runTest {
        val forgotten = mutableListOf<String>()
        val automations = Automations(backend, alarms, config, EpochClock { now }, IdGenerator { "a${nextId++}" }, { zone }, this, forgetEvents = { forgotten += it })
        val saved = assertIs<SaveResult.Saved>(automations.save(automations.draft("Brief", "Brief me"))).automation
        automations.delete(saved.id)
        assertEquals(listOf(saved.id), forgotten)
    }

    @Test
    fun `refuses what automations json doesn't allow`() = runTest {
        val automations = automations()
        val draft = automations.draft("Brief", "Brief me")
        assertEquals(AutomationProblem.NAME_EMPTY, refusal(automations.save(draft.copy(name = "  "))))
        assertEquals(AutomationProblem.NAME_TOO_LONG, refusal(automations.save(draft.copy(name = "x".repeat(21)))))
        assertEquals(AutomationProblem.PROMPT_EMPTY, refusal(automations.save(draft.copy(prompt = ""))))
        assertEquals(AutomationProblem.PROMPT_TOO_LONG, refusal(automations.save(draft.copy(prompt = "x".repeat(101)))))
        assertEquals(AutomationProblem.TOOL_NOT_ALLOWED, refusal(automations.save(draft.copy(tools = setOf("compose_message")))))
        assertTrue(backend.stored.isEmpty())
        assertTrue(alarms.set.isEmpty())
    }

    @Test
    fun `stops at the most automations allowed`() = runTest {
        val automations = automations()
        repeat(2) { assertIs<SaveResult.Saved>(automations.save(automations.draft("Brief $it", "Brief me"))) }
        assertEquals(AutomationProblem.TOO_MANY, refusal(automations.save(automations.draft("One more", "Brief me"))))
        // Editing one that's already there is still fine.
        assertIs<SaveResult.Saved>(automations.save(automations.all.value.first().copy(prompt = "Changed")))
    }

    @Test
    fun `a run may use its chosen tools and the harness tools, nothing else`() = runTest {
        val automations = automations()
        val automation = automations.draft("Brief", "Brief me").copy(tools = setOf("calendar_events", "compose_message"))
        assertEquals(setOf(ToolId("calendar_events"), ToolId("update_plan")), automations.preApprovedTools(automation))
    }

    @Test
    fun `a retry is its own alarm and keeps the time it was due`() = runTest {
        val automations = automations()
        automations.retryLater("a0", scheduledForMillis = now - 5)
        assertEquals(Alarm("a0", now + config.retryAfterMillis, now - 5, retry = true), alarms.set.single())
    }

    @Test
    fun `rescheduling sets an alarm for every enabled automation and cancels the rest`() = runTest {
        val automations = automations()
        val on = assertIs<SaveResult.Saved>(automations.save(automations.draft("On", "Brief me"))).automation
        val off = assertIs<SaveResult.Saved>(automations.save(automations.draft("Off", "Brief me"))).automation
        automations.setEnabled(off.id, false)
        alarms.set.clear()
        alarms.cancelled.clear()
        automations.rescheduleAll()
        assertEquals(listOf(on.id), alarms.set.map { it.id })
        assertEquals(listOf(off.id), alarms.cancelled)
    }

    @Test
    fun `a missed run is noted until the next one starts`() = runTest {
        val automations = automations()
        val saved = assertIs<SaveResult.Saved>(automations.save(automations.draft("Brief", "Brief me"))).automation
        automations.recordMissed(saved.id, scheduledForMillis = now - 1)
        assertEquals(now - 1, automations.all.value.single().missedAtMillis)
        // An edit doesn't clear it; a run does.
        automations.save(automations.all.value.single().copy(name = "Morning"))
        assertEquals(now - 1, automations.all.value.single().missedAtMillis)
        automations.recordRun(saved.id, chatId = "chat", atMillis = now)
        assertNull(automations.all.value.single().missedAtMillis)
    }

    @Test
    fun `a project must be a plain folder name, and blank means the whole workspace`() = runTest {
        val automations = automations()
        val draft = automations.draft("Brief", "Brief me")
        assertEquals(AutomationProblem.PROJECT_INVALID, refusal(automations.save(draft.copy(project = "../etc"))))
        assertEquals("notes", assertIs<SaveResult.Saved>(automations.save(draft.copy(project = " notes/ "))).automation.project)
        assertNull(assertIs<SaveResult.Saved>(automations.save(draft.copy(project = " "))).automation.project)
    }

    private fun refusal(result: SaveResult) = assertIs<SaveResult.Refused>(result).problem
}
