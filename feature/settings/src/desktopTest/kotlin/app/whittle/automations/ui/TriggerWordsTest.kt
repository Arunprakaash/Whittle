package app.whittle.automations.ui

import app.whittle.automations.Edge
import app.whittle.automations.Trigger
import app.whittle.automations.capabilities
import app.whittle.automations.readsMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerWordsTest {
    private val words = TriggerStrings(
        every = { "Every $it minutes" },
        smsAny = "When a text comes in",
        smsFrom = { "When a text comes from $it" },
        notification = { "When $it notifies you" },
        placeArrive = { "When you arrive at $it" },
        placeLeave = { "When you leave $it" },
        plugIn = "When you plug in",
        unplug = "When you unplug",
        wifiJoinAny = "When you join Wi-Fi",
        wifiLeaveAny = "When you leave Wi-Fi",
        wifiJoin = { "When you join $it" },
        wifiLeave = { "When you leave $it" },
        withWords = { base, listed -> "$base, saying $listed" },
        or = "or",
    )

    @Test
    fun describesTheKindsInWords() {
        assertEquals("When a text comes from HDFCBK", describe(Trigger.Sms(listOf("HDFCBK"), emptyList()), words))
        assertEquals("When you unplug", describe(Trigger.Charging(Edge.END), words))
        assertEquals("When you plug in", describe(Trigger.Charging(Edge.START), words))
        assertEquals("Every 30 minutes", describe(Trigger.Every(30), words))
        assertEquals("When you leave Home", describe(Trigger.Place("Home", 1.0, 2.0, 200, Edge.END), words))
        assertEquals("When you join Wi-Fi", describe(Trigger.Wifi(null, Edge.START), words))
        assertEquals("When you leave Cafe", describe(Trigger.Wifi("Cafe", Edge.END), words))
        assertEquals("When Bank notifies you", describe(Trigger.Notification("com.bank", "Bank"), words))
    }

    @Test
    fun anySenderAndListsReadNaturally() {
        assertEquals("When a text comes in", describe(Trigger.Sms(), words))
        assertEquals("When a text comes from A, B or C", describe(Trigger.Sms(listOf("A", "B", "C")), words))
    }

    @Test
    fun wordsAreAddedWhenThereAreAny() {
        assertEquals(
            "When a text comes from HDFCBK, saying debited or credited",
            describe(Trigger.Sms(listOf("HDFCBK"), listOf("debited", "credited")), words),
        )
        assertEquals("When Bank notifies you, saying alert", describe(Trigger.Notification("com.bank", "Bank", listOf("alert")), words))
    }

    @Test
    fun parsesCommaListsAndKeepsOrder() {
        assertEquals(listOf("a", "b c"), parseWords(" a, b c ,, "))
        assertEquals(emptyList(), parseWords("  "))
        assertEquals("a, b", listWords(listOf("a", "b")))
    }

    @Test
    fun whatEachTriggerNeedsComesFromConfigAndAnyNetworkNeedsNothing() {
        val byType = mapOf("sms" to listOf("sms"), "notification" to listOf("notification_access"), "place" to listOf("location", "location_background"), "wifi" to listOf("location"))
        assertEquals(listOf("sms"), Trigger.Sms().capabilities(byType))
        assertEquals(listOf("notification_access"), Trigger.Notification("a", "A").capabilities(byType))
        assertEquals(listOf("location", "location_background"), Trigger.Place("p", 0.0, 0.0, 100, Edge.START).capabilities(byType))
        assertEquals(listOf("location"), Trigger.Wifi("Home", Edge.START).capabilities(byType))
        assertEquals(emptyList(), Trigger.Wifi(null, Edge.END).capabilities(byType))
        assertEquals(emptyList(), Trigger.Charging(Edge.START).capabilities(byType))
        assertEquals(emptyList(), Trigger.Every(60).capabilities(byType))
    }

    @Test
    fun onlyTextsAndNotificationsReadWhatOthersWrote() {
        assertTrue(Trigger.Sms().readsMessages && Trigger.Notification("a", "A").readsMessages)
        assertFalse(Trigger.Charging(Edge.START).readsMessages || Trigger.Every(15).readsMessages || Trigger.Wifi(null, Edge.START).readsMessages)
    }
}
