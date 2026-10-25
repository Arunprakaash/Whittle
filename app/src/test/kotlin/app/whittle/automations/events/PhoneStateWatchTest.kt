package app.whittle.automations.events

import app.whittle.automations.Automations
import app.whittle.automations.Edge
import app.whittle.automations.EventKind
import app.whittle.automations.PhoneEvent
import app.whittle.automations.Trigger
import app.whittle.automations.matches
import app.whittle.automations.MemoryBackend
import app.whittle.automations.RecordingAlarms
import app.whittle.automations.testAutomationsConfig
import app.whittle.automations.testEventsConfig
import app.whittle.core.common.EpochClock
import app.whittle.core.common.IdGenerator
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhoneStateWatchTest {
    private val now = 1_800_000_000_000L
    private val home = Trigger.Place("Home", 12.9716, 77.5946, 100, Edge.START)
    private val idle = PhoneState(charging = false, wifi = null, inside = emptySet())

    @Test
    fun pluggingInStartsChargingOnce() {
        val plugged = idle.copy(charging = true)

        assertEquals(listOf(PhoneEvent(EventKind.CHARGING, "", "", now, Edge.START)), edges(idle, plugged, emptyList(), now))
        assertTrue(edges(plugged, plugged, emptyList(), now).isEmpty())
        assertEquals(Edge.END, edges(plugged, idle, emptyList(), now).single().edge)
    }

    @Test
    fun joiningWifiGivesStartAndLeavingGivesEnd() {
        val joined = idle.copy(wifi = "Home")

        assertEquals(PhoneEvent(EventKind.WIFI, "Home", "", now, Edge.START), edges(idle, joined, emptyList(), now).single())
        assertEquals(PhoneEvent(EventKind.WIFI, "Home", "", now, Edge.END), edges(joined, idle, emptyList(), now).single())
    }

    @Test
    fun leavingWifiGivesEnd() {
        val events = edges(idle.copy(wifi = "Home"), idle, emptyList(), now)

        assertEquals(listOf(Edge.END), events.map { it.edge })
    }

    @Test
    fun enteringPlaceRadiusGivesArrive() {
        // About 55 m north of the point is inside a 100 m radius; about 220 m is not.
        val near = insideOf(12.9721, 77.5946, listOf(home))
        val far = insideOf(12.9736, 77.5946, listOf(home))

        assertEquals(setOf("Home"), near)
        assertTrue(far.isEmpty())
        assertEquals(
            PhoneEvent(EventKind.PLACE, "Home", "", now, Edge.START),
            edges(idle, idle.copy(inside = near), listOf(home), now).single(),
        )
        assertEquals(Edge.END, edges(idle.copy(inside = near), idle.copy(inside = far), listOf(home), now).single().edge)
    }

    @Test
    fun noChangeGivesNothing() {
        val state = PhoneState(charging = true, wifi = "Home", inside = setOf("Home"))

        assertTrue(edges(state, state, listOf(home), now).isEmpty())
    }

    @Test
    fun unknownChargingGivesNoEdge() {
        assertTrue(edges(idle.copy(charging = null), idle.copy(charging = true), emptyList(), now).isEmpty())
    }

    @Test
    fun aPlaceTheUserDeletedGivesNoEvent() {
        assertTrue(edges(idle, idle.copy(inside = setOf("Gone")), listOf(home), now).isEmpty())
    }

    @Test
    fun needsFollowEnabledEventAutomationsOnly() {
        val triggers = listOf(Trigger.Charging(Edge.START), Trigger.Wifi(null, Edge.END), home, home.copy(edge = Edge.END), Trigger.Every(30))

        val needs = needsOf(triggers)

        assertTrue(needs.charging && needs.wifi)
        assertEquals(listOf("Home"), needs.places.map { it.name })
        assertEquals(PhoneNeeds(), needsOf(listOf(Trigger.Every(30), Trigger.Sms())))
    }

    private class Fake(var state: PhoneState = PhoneState(false, null, emptySet())) : PhoneReader, PhoneStateStore, PhoneScheduler {
        var saved: PhoneState? = null
        var location: Pair<Double, Double>? = null
        var lastKnown: Pair<Double, Double>? = null
        var armed: PhoneNeeds? = null
        var locationReads = 0

        override fun charging() = state.charging
        override fun wifi() = state.wifi
        override suspend fun location(): Pair<Double, Double>? = location.also { locationReads++ }
        override fun lastKnownLocation(): Pair<Double, Double>? = lastKnown
        override suspend fun load() = saved
        override suspend fun save(state: PhoneState?) { saved = state }
        override fun arm(needs: PhoneNeeds) { armed = needs }
    }

    private suspend fun watch(fake: Fake, scope: TestScope, triggers: List<Trigger>, polling: Boolean = false, events: MutableList<PhoneEvent>): PhoneStateWatch {
        val automations = Automations(
            MemoryBackend(), RecordingAlarms(), testAutomationsConfig.copy(maxAutomations = 10),
            EpochClock { now }, IdGenerator { "x" }, { ZoneId.of("Asia/Kolkata") }, scope,
        )
        triggers.forEachIndexed { i, t -> automations.save(automations.draft("a$i", "Do it").copy(id = "a$i", schedule = null, trigger = t)) }
        return PhoneStateWatch(automations, { events += it }, testEventsConfig.copy(placePolling = polling), fake, fake, fake, EpochClock { now })
    }

    @Test
    fun theFirstCheckOnlyRecordsAndLaterOnesDispatch() = runTest {
        val fake = Fake()
        val events = mutableListOf<PhoneEvent>()
        val watch = watch(fake, this, listOf(Trigger.Charging(Edge.START)), events = events)

        watch.check()
        assertTrue(events.isEmpty())
        assertEquals(idle, fake.saved)

        fake.state = idle.copy(charging = true)
        watch.check()
        watch.check()

        assertEquals(listOf(EventKind.CHARGING), events.map { it.kind })
        assertEquals(PhoneNeeds(charging = true), fake.armed)
    }

    @Test
    fun nothingNeededReadsNothingAndForgetsTheState() = runTest {
        val fake = Fake().also { it.saved = idle }
        val events = mutableListOf<PhoneEvent>()
        val watch = watch(fake, this, listOf(Trigger.Every(30)), polling = true, events = events)

        watch.check()

        assertNull(fake.saved)
        assertEquals(PhoneNeeds(), fake.armed)
        assertEquals(0, fake.locationReads)
    }

    @Test
    fun locationIsReadOnlyWhenPollingAndAPlaceIsOn() = runTest {
        val fake = Fake().also { it.location = 12.9721 to 77.5946 }
        val events = mutableListOf<PhoneEvent>()
        val off = watch(fake, this, listOf(home), polling = false, events = events)
        off.check()
        assertEquals(0, fake.locationReads)

        val on = watch(fake, this, listOf(home), polling = true, events = events)
        on.check()

        assertEquals(1, fake.locationReads)
        assertEquals(EventKind.PLACE, events.single().kind)
        assertEquals(setOf("Home"), fake.saved?.inside)
    }

    @Test
    fun aProximityAlertDispatchesOnceForAnEdge() = runTest {
        val fake = Fake().also { it.saved = idle }
        val events = mutableListOf<PhoneEvent>()
        val watch = watch(fake, this, listOf(home), events = events)

        watch.placeCrossed("Home", entering = true)
        watch.placeCrossed("Home", entering = true)
        watch.placeCrossed("Elsewhere", entering = true)

        assertEquals(listOf(Edge.START), events.map { it.edge })
    }

    @Test
    fun joiningAHiddenNameNetworkStartsWifiForAnyNetworkTrigger() {
        val joined = idle.copy(wifi = wifiState(onWifi = true, ssid = "<unknown ssid>"))

        assertEquals("", joined.wifi)
        assertNull(wifiState(onWifi = false, ssid = "\"Home\""))
        assertEquals("Home", wifiState(onWifi = true, ssid = "\"Home\""))
        val event = edges(idle, joined, emptyList(), now).single()
        assertEquals(Edge.START, event.edge)
        assertTrue(Trigger.Wifi(null, Edge.START).matches(event))
        assertTrue(!Trigger.Wifi("Home", Edge.START).matches(event))
    }

    @Test
    fun armingRecordsABaselineSoTheFirstChangeFires() = runTest {
        val fake = Fake()
        val events = mutableListOf<PhoneEvent>()
        val watch = watch(fake, this, listOf(Trigger.Charging(Edge.START)), events = events)

        watch.arm()
        assertEquals(idle, fake.saved)
        fake.state = idle.copy(charging = true)
        watch.check()

        assertEquals(listOf(EventKind.CHARGING), events.map { it.kind })
    }

    @Test
    fun aProximityAlertWithNoSavedStateOnlyRecords() = runTest {
        val fake = Fake()
        val events = mutableListOf<PhoneEvent>()
        val watch = watch(fake, this, listOf(home), events = events)

        watch.placeCrossed("Home", entering = true)

        assertTrue(events.isEmpty())
        assertEquals(setOf("Home"), fake.saved?.inside)
    }

    @Test
    fun aNameThatGetsHiddenWhileStillOnWifiIsNotLeavingIt() = runTest {
        val fake = Fake(idle.copy(wifi = "Home"))
        val events = mutableListOf<PhoneEvent>()
        val watch = watch(fake, this, listOf(Trigger.Wifi(null, Edge.START), Trigger.Wifi("Home", Edge.END)), events = events)

        watch.check()
        fake.state = idle.copy(wifi = "")
        watch.check()
        assertEquals("Home", fake.saved?.wifi)
        fake.state = idle.copy(wifi = "Home")
        watch.check()
        assertTrue(events.isEmpty(), events.toString())

        fake.state = idle.copy(wifi = "")
        watch.check()
        fake.state = idle
        watch.check()
        assertEquals(listOf(PhoneEvent(EventKind.WIFI, "Home", "", now, Edge.END)), events)
    }

    @Test
    fun aPlaceArmedWhileTheCurrentSpotIsInsideDoesNotFireOnTheFirstEnter() = runTest {
        val fake = Fake().also { it.lastKnown = 12.9721 to 77.5946 }
        val events = mutableListOf<PhoneEvent>()
        val watch = watch(fake, this, listOf(home), events = events)

        watch.arm()
        assertEquals(setOf("Home"), fake.saved?.inside)
        watch.placeCrossed("Home", entering = true)
        assertTrue(events.isEmpty(), events.toString())

        // Leaving and coming back are real.
        watch.placeCrossed("Home", entering = false)
        watch.placeCrossed("Home", entering = true)
        assertEquals(listOf(Edge.END, Edge.START), events.map { it.edge })
    }

    @Test
    fun aPlaceAddedLaterIsLookedAtToo() = runTest {
        val fake = Fake().also { it.saved = idle.copy(baselined = setOf("Work")); it.lastKnown = 12.9721 to 77.5946 }
        val events = mutableListOf<PhoneEvent>()
        val watch = watch(fake, this, listOf(Trigger.Place("Work", 1.0, 1.0, 100, Edge.START), home), events = events)

        watch.arm()
        watch.placeCrossed("Home", entering = true)

        assertEquals(setOf("Home"), fake.saved?.inside)
        assertEquals(setOf("Work", "Home"), fake.saved?.baselined)
        assertTrue(events.isEmpty())
    }

    @Test
    fun withNoLastLocationAnEnterStillFires() = runTest {
        val fake = Fake().also { it.saved = idle }
        val events = mutableListOf<PhoneEvent>()
        val watch = watch(fake, this, listOf(home), events = events)

        watch.arm()
        assertTrue(fake.saved?.baselined.orEmpty().isEmpty())
        watch.placeCrossed("Home", entering = true)

        assertEquals(listOf(Edge.START), events.map { it.edge })
    }
}
