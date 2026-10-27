package app.whittle.automations

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneEventTest {
    @Test
    fun smsMatchesSenderCaseInsensitivelyAndAnyWord() {
        val trigger = Trigger.Sms(listOf("hdfcbk"), listOf("credited", "debited"))
        assertTrue(trigger.matches(PhoneEvent(EventKind.SMS, "VM-HDFCBK", "Rs 500 debited", 0), SUFFIX))
        assertFalse(trigger.matches(PhoneEvent(EventKind.SMS, "VM-HDFCBK", "Rs 500 refunded", 0), SUFFIX))
        assertFalse(trigger.matches(PhoneEvent(EventKind.SMS, "VM-SBIBNK", "Rs 500 debited", 0), SUFFIX))
        assertTrue(Trigger.Sms().matches(PhoneEvent(EventKind.SMS, "anyone", "anything", 0), SUFFIX))
    }

    @Test
    fun aTriggerOnlyMatchesItsOwnKindOfEvent() {
        assertFalse(Trigger.Sms().matches(PhoneEvent(EventKind.NOTIFICATION, "app", "hi", 0), SUFFIX))
        assertFalse(Trigger.Every(15).matches(PhoneEvent(EventKind.SMS, "a", "b", 0), SUFFIX))
    }

    @Test
    fun notificationNeedsTheAppAndAnyWord() {
        val trigger = Trigger.Notification("com.bank", "Bank", listOf("Alert"))
        assertTrue(trigger.matches(PhoneEvent(EventKind.NOTIFICATION, "com.bank", "New alert", 0), SUFFIX))
        assertFalse(trigger.matches(PhoneEvent(EventKind.NOTIFICATION, "com.other", "New alert", 0), SUFFIX))
        assertFalse(trigger.matches(PhoneEvent(EventKind.NOTIFICATION, "com.bank", "Hello", 0), SUFFIX))
    }

    @Test
    fun edgeMustMatch() {
        val trigger = Trigger.Charging(Edge.START)
        assertTrue(trigger.matches(PhoneEvent(EventKind.CHARGING, "", "", 0, Edge.START), SUFFIX))
        assertFalse(trigger.matches(PhoneEvent(EventKind.CHARGING, "", "", 0, Edge.END), SUFFIX))
        assertFalse(trigger.matches(PhoneEvent(EventKind.CHARGING, "", "", 0), SUFFIX))
    }

    @Test
    fun wifiNullNetworkMatchesAny() {
        val any = Trigger.Wifi(null, Edge.START)
        assertTrue(any.matches(PhoneEvent(EventKind.WIFI, "Home", "", 0, Edge.START), SUFFIX))
        val home = Trigger.Wifi("Home", Edge.START)
        assertTrue(home.matches(PhoneEvent(EventKind.WIFI, "Home", "", 0, Edge.START), SUFFIX))
        assertFalse(home.matches(PhoneEvent(EventKind.WIFI, "Cafe", "", 0, Edge.START), SUFFIX))
    }

    @Test
    fun placeMatchesByNameAndEdge() {
        val trigger = Trigger.Place("Office", 12.9, 77.6, 150, Edge.START)
        assertTrue(trigger.matches(PhoneEvent(EventKind.PLACE, "Office", "", 0, Edge.START), SUFFIX))
        assertFalse(trigger.matches(PhoneEvent(EventKind.PLACE, "Office", "", 0, Edge.END), SUFFIX))
        assertFalse(trigger.matches(PhoneEvent(EventKind.PLACE, "Home", "", 0, Edge.START), SUFFIX))
    }

    @Test
    fun aSenderIdMatchesWholeOrAsAnOperatorWrappedPartNeverInsideAnother() {
        assertTrue(matchesSender("VM-HDFCBK", "HDFCBK", SUFFIX))
        assertTrue(matchesSender("AD-HDFCBK-S", "hdfcbk", SUFFIX))
        assertTrue(matchesSender("HDFCBK", "HDFCBK", SUFFIX))
        assertFalse(matchesSender("VM-FAKEHDFCBK", "HDFCBK", SUFFIX))
        assertFalse(matchesSender("HDFCBKX", "HDFCBK", SUFFIX))
        assertFalse(matchesSender("+919876543210", "HDFCBK", SUFFIX))
    }

    @Test
    fun aNumberMatchesByItsDigitsAndOnlyALongOneMayLeaveOutTheCountryCode() {
        assertTrue(matchesSender("+919876543210", "98765 43210", SUFFIX))
        assertTrue(matchesSender("+91 98765-43210", "+919876543210", SUFFIX))
        // A stranger's number that merely contains the wanted digits isn't it.
        assertFalse(matchesSender("+919876543210", "765432", SUFFIX))
        assertTrue(matchesSender("57575", "57575", SUFFIX))
        assertFalse(matchesSender("157575", "57575", SUFFIX))
        assertFalse(matchesSender("VM-57575", "57575", SUFFIX))
        assertFalse(matchesSender("anyone", " ", SUFFIX))
    }

    private companion object {
        const val SUFFIX = 8
    }
}
