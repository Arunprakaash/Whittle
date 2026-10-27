package app.whittle.automations.events

import app.whittle.automations.Automation
import app.whittle.automations.Edge
import app.whittle.automations.Schedule
import app.whittle.automations.Trigger
import app.whittle.automations.testEventsConfig
import app.whittle.phone.PhoneActionsConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventSourcesTest {
    private val byType = testEventsConfig.capabilities
    private val switches = listOf(
        PhoneActionsConfig.Switch("texts", "sms", "Texts", listOf("sms"), emptyList()),
        PhoneActionsConfig.Switch("location", "location_on", "Location", listOf("location", "location_background"), emptyList()),
    )

    private fun automation(id: String, trigger: Trigger?, enabled: Boolean = true) = Automation(
        id = id, name = id, prompt = "Do it", schedule = if (trigger == null) Schedule(420, Schedule.EVERY_DAY) else null,
        tools = emptySet(), enabled = enabled, trigger = trigger,
    )

    @Test
    fun `a trigger runs only with what it needs granted and its switch on`() {
        val sms = Trigger.Sms()
        assertTrue(triggerAllowed(sms, byType, switches, off = emptySet()) { true })
        assertFalse(triggerAllowed(sms, byType, switches, off = emptySet()) { false })
        assertFalse(triggerAllowed(sms, byType, switches, off = setOf("texts")) { true })
        // Charging needs nothing, so neither a refusal nor a switch stops it.
        assertTrue(triggerAllowed(Trigger.Charging(Edge.START), byType, switches, off = setOf("texts", "location")) { false })
        // A named network needs location; any network doesn't.
        assertFalse(triggerAllowed(Trigger.Wifi("Home", Edge.START), byType, switches, off = setOf("location")) { true })
        assertTrue(triggerAllowed(Trigger.Wifi(null, Edge.START), byType, switches, off = setOf("location")) { true })
    }

    @Test
    fun `the text receiver and the listener are wanted only for enabled automations that may run`() {
        val all = listOf(
            automation("brief", null),
            automation("bank", Trigger.Sms(listOf("HDFCBK"))),
            automation("chat", Trigger.Notification("com.chat", "Chat")),
            automation("off", Trigger.Notification("com.off", "Off"), enabled = false),
            automation("poll", Trigger.Every(30)),
        )
        val everything = sourceNeeds(allowedTriggers(all) { true })
        assertTrue(everything.texts)
        assertEquals(setOf("com.chat"), everything.notificationApps)
        // Texts switched off: no receiver, so no process start for each text.
        val noTexts = sourceNeeds(allowedTriggers(all) { it !is Trigger.Sms })
        assertFalse(noTexts.texts)
        assertEquals(SourceNeeds(), sourceNeeds(allowedTriggers(listOf(automation("brief", null), automation("poll", Trigger.Every(30)))) { true }))
    }

    @Test
    fun `a periodic look is scheduled only for an edge nothing else wakes Whittle for`() {
        // Plugging in has its own job, joining Wi-Fi its callback, and places their alerts.
        val plugIn = needsOf(listOf(Trigger.Charging(Edge.START)))
        assertFalse(plugIn.needsPeriodicLook(chargingNow = false, placePolling = false))
        // While plugged in, only a look sees the unplug that arms the charger job again.
        assertTrue(plugIn.needsPeriodicLook(chargingNow = true, placePolling = false))
        assertFalse(needsOf(listOf(Trigger.Wifi("Home", Edge.START))).needsPeriodicLook(chargingNow = null, placePolling = false))
        assertTrue(needsOf(listOf(Trigger.Charging(Edge.END))).needsPeriodicLook(chargingNow = false, placePolling = false))
        assertTrue(needsOf(listOf(Trigger.Wifi(null, Edge.END))).needsPeriodicLook(chargingNow = false, placePolling = false))
        val place = needsOf(listOf(Trigger.Place("Home", 1.0, 2.0, 200, Edge.END)))
        assertFalse(place.needsPeriodicLook(chargingNow = false, placePolling = false))
        assertTrue(place.needsPeriodicLook(chargingNow = false, placePolling = true))
        assertFalse(PhoneNeeds().needsPeriodicLook(chargingNow = true, placePolling = true))
    }
}
