package app.whittle.automations.events

import app.whittle.automations.Automations
import app.whittle.automations.EventsConfig
import app.whittle.automations.PhoneEvent
import app.whittle.core.common.EpochClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the phone's state is read from; every Android call lives behind this. A null is unknown, or not connected. */
interface PhoneReader {
    fun charging(): Boolean?

    /** The name of the network the phone is on; null when it has none or Android won't say (no location permission). */
    fun wifi(): String?

    /** Where the phone is, latitude then longitude; null without the permission or a fix. */
    suspend fun location(): Pair<Double, Double>?
}

/** Where the last [PhoneState] is kept; saving null forgets it. */
interface PhoneStateStore {
    suspend fun load(): PhoneState?

    suspend fun save(state: PhoneState?)
}

/** Wakes Whittle for what [PhoneNeeds] asks, and nothing else; an empty need cancels everything. */
interface PhoneScheduler {
    fun arm(needs: PhoneNeeds)
}

/**
 * Watches charging, Wi-Fi and places for the automations that start on them. Android wakes it (a job, a network
 * callback, a proximity alert); each wake calls [check], which diffs the phone against what it last saw and
 * hands the edges to [dispatch]. The first look after a source starts being watched only records where things stand.
 */
class PhoneStateWatch(
    private val automations: Automations,
    private val dispatch: suspend (PhoneEvent) -> Unit,
    private val config: EventsConfig,
    private val reader: PhoneReader,
    private val store: PhoneStateStore,
    private val scheduler: PhoneScheduler,
    private val clock: EpochClock,
) {
    private val lock = Mutex()

    /** Arms again whenever the enabled automations' charging, Wi-Fi or place triggers change; once loaded, from then on. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        automations.loadedAll()
        automations.all.map { needsOf(enabledTriggers(it)) }.distinctUntilChanged().collect { arm(it) }
    }

    /** Arms what the saved automations need, for a wake in a fresh process that [start] hasn't reached. */
    suspend fun arm() = arm(needs())

    private suspend fun arm(needs: PhoneNeeds) = lock.withLock {
        // Nothing watched means nothing saved: watching later starts from where things stand then.
        if (!needs.any) store.save(null) else if (store.load() == null) store.save(read(null, needs))
        scheduler.arm(needs)
    }

    /** Reads the phone, dispatches what changed since the last look, saves it, and arms again (a charging job runs once). */
    suspend fun check() = lock.withLock {
        val needs = needs()
        if (needs.any) {
            val before = store.load()
            val after = read(before, needs)
            if (before != null) edges(before, after, needs.places, clock.nowMillis()).forEach { dispatch(it) }
            store.save(after)
        } else {
            store.save(null)
        }
        scheduler.arm(needs)
    }

    /** A proximity alert said the phone entered or left the place called [name]. */
    suspend fun placeCrossed(name: String, entering: Boolean) = lock.withLock {
        val needs = needs()
        if (needs.places.none { it.name == name }) return@withLock
        val before = store.load()
        if (before == null) {
            // Nothing seen yet, so this only records where things stand.
            store.save(PhoneState(reader.charging(), reader.wifi(), if (entering) setOf(name) else emptySet()))
            return@withLock
        }
        val after = before.copy(inside = if (entering) before.inside + name else before.inside - name)
        if (after == before) return@withLock
        edges(before, after, needs.places, clock.nowMillis()).forEach { dispatch(it) }
        store.save(after)
    }

    private suspend fun needs(): PhoneNeeds = needsOf(enabledTriggers(automations.loadedAll()))

    private fun enabledTriggers(all: List<app.whittle.automations.Automation>) = all.filter { it.enabled && it.isEvent }.mapNotNull { it.trigger }

    /** Charging and Wi-Fi are cheap, so both are always read; a location is read only when polling is on and a place needs it. */
    private suspend fun read(before: PhoneState?, needs: PhoneNeeds): PhoneState {
        val known = needs.places.map { it.name }.toSet()
        val last = before?.inside.orEmpty().filter { it in known }.toSet()
        val inside = if (config.placePolling && needs.places.isNotEmpty()) {
            reader.location()?.let { (latitude, longitude) -> insideOf(latitude, longitude, needs.places) } ?: last
        } else {
            last
        }
        return PhoneState(reader.charging(), reader.wifi(), inside)
    }
}
