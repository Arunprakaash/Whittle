package app.whittle.automations

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerTest {
    @Test
    fun smsMatchesSenderCaseInsensitivelyAndAnyWord() {
        val trigger = Trigger.Sms(listOf("hdfcbk"), listOf("credited", "debited"))
        assertTrue(trigger.matches(PhoneEvent(EventKind.SMS, "VM-HDFCBK", "Rs 500 debited", 0)))
        assertFalse(trigger.matches(PhoneEvent(EventKind.SMS, "VM-HDFCBK", "Rs 500 refunded", 0)))
        assertFalse(trigger.matches(PhoneEvent(EventKind.SMS, "VM-SBIBNK", "Rs 500 debited", 0)))
        assertTrue(Trigger.Sms().matches(PhoneEvent(EventKind.SMS, "anyone", "anything", 0)))
    }

    @Test
    fun aTriggerOnlyMatchesItsOwnKindOfEvent() {
        assertFalse(Trigger.Sms().matches(PhoneEvent(EventKind.NOTIFICATION, "app", "hi", 0)))
        assertFalse(Trigger.Every(15).matches(PhoneEvent(EventKind.SMS, "a", "b", 0)))
    }

    @Test
    fun notificationNeedsTheAppAndAnyWord() {
        val trigger = Trigger.Notification("com.bank", "Bank", listOf("Alert"))
        assertTrue(trigger.matches(PhoneEvent(EventKind.NOTIFICATION, "com.bank", "New alert", 0)))
        assertFalse(trigger.matches(PhoneEvent(EventKind.NOTIFICATION, "com.other", "New alert", 0)))
        assertFalse(trigger.matches(PhoneEvent(EventKind.NOTIFICATION, "com.bank", "Hello", 0)))
    }

    @Test
    fun edgeMustMatch() {
        val trigger = Trigger.Charging(Edge.START)
        assertTrue(trigger.matches(PhoneEvent(EventKind.CHARGING, "", "", 0, Edge.START)))
        assertFalse(trigger.matches(PhoneEvent(EventKind.CHARGING, "", "", 0, Edge.END)))
        assertFalse(trigger.matches(PhoneEvent(EventKind.CHARGING, "", "", 0)))
    }

    @Test
    fun wifiNullNetworkMatchesAny() {
        val any = Trigger.Wifi(null, Edge.START)
        assertTrue(any.matches(PhoneEvent(EventKind.WIFI, "Home", "", 0, Edge.START)))
        val home = Trigger.Wifi("Home", Edge.START)
        assertTrue(home.matches(PhoneEvent(EventKind.WIFI, "Home", "", 0, Edge.START)))
        assertFalse(home.matches(PhoneEvent(EventKind.WIFI, "Cafe", "", 0, Edge.START)))
    }

    @Test
    fun placeMatchesByNameAndEdge() {
        val trigger = Trigger.Place("Office", 12.9, 77.6, 150, Edge.START)
        assertTrue(trigger.matches(PhoneEvent(EventKind.PLACE, "Office", "", 0, Edge.START)))
        assertFalse(trigger.matches(PhoneEvent(EventKind.PLACE, "Office", "", 0, Edge.END)))
        assertFalse(trigger.matches(PhoneEvent(EventKind.PLACE, "Home", "", 0, Edge.START)))
    }
}
