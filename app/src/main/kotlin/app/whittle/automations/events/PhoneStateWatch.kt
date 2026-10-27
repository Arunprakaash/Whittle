package app.whittle.automations.events

import app.whittle.automations.Automation
import app.whittle.automations.Automations
import app.whittle.automations.EventsConfig
import app.whittle.automations.PhoneEvent
import app.whittle.automations.Trigger
import app.whittle.core.common.EpochClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the phone's state is read from; every Android call lives behind this. A null is unknown, or not connected. */
interface PhoneReader {
    fun charging(): Boolean?

    /** The name of the network the phone is on; null when it has none or Android won't say (no location permission). */
    fun wifi(): String?

    /** Where the phone is, latitude then longitude; null without the permission or a fix. */
    suspend fun location(): Pair<Double, Double>?

    /** Where the phone was last seen, without asking for a new fix; null without the permission or any record. */
    fun lastKnownLocation(): Pair<Double, Double>?
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
 * A trigger counts only while [allows] it: what it needs is granted and its switch in Settings is on.
 */
class PhoneStateWatch(
    private val automations: Automations,
    private val dispatch: suspend (PhoneEvent) -> Unit,
    private val config: EventsConfig,
    private val reader: PhoneReader,
    private val store: PhoneStateStore,
    private val scheduler: PhoneScheduler,
    private val clock: EpochClock,
    private val allows: (Trigger) -> Boolean = { true },
) {
    private val lock = Mutex()

    /** Arms what the saved automations need: when they or what the phone allows change, and on a wake in a fresh process. */
    suspend fun arm() = arm(needs())

    private suspend fun arm(needs: PhoneNeeds) = lock.withLock {
        // Nothing watched means nothing saved: watching later starts from where things stand then.
        if (!needs.any) {
            store.save(null)
        } else {
            val saved = store.load()
            // A place armed now is looked up at once, so an alert that fires for a phone already there finds it inside.
            if (saved == null) store.save(baselined(read(null, needs), needs)) else baselined(saved, needs).takeIf { it != saved }?.let { store.save(it) }
        }
        scheduler.arm(needs)
    }

    /** Reads the phone, dispatches what changed since the last look, saves it, and arms again (a charging job runs once). */
    suspend fun check() = lock.withLock {
        val needs = needs()
        if (needs.any) {
            val before = store.load()?.let { baselined(it, needs) }
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
        val before = store.load()?.let { baselined(it, needs) }
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

    private fun enabledTriggers(all: List<Automation>) = all.filter { it.enabled && it.isEvent }.mapNotNull { it.trigger }.filter(allows)

    /** Charging and Wi-Fi are cheap, so both are always read; a location is read only when polling is on and a place needs it. */
    private suspend fun read(before: PhoneState?, needs: PhoneNeeds): PhoneState {
        val known = needs.places.map { it.name }.toSet()
        val last = before?.inside.orEmpty().filter { it in known }.toSet()
        val polling = config.placePolling && needs.places.isNotEmpty()
        val inside = if (polling) {
            reader.location()?.let { (latitude, longitude) -> insideOf(latitude, longitude, needs.places) } ?: last
        } else {
            last
        }
        val baselined = if (polling) known else before?.baselined.orEmpty().filter { it in known }.toSet()
        return PhoneState(reader.charging(), wifiKeepingName(before?.wifi), inside, baselined)
    }

    /**
     * [state] with each place not yet looked at set from one passive look at where the phone was last seen, no new
     * fix: Android's proximity alert reports "entering" for a phone that is already there, which would otherwise
     * read as arriving. Without a last location the place stays unlooked-at and is tried again next time.
     */
    private fun baselined(state: PhoneState, needs: PhoneNeeds): PhoneState {
        val fresh = needs.places.filter { it.name !in state.baselined }
        if (fresh.isEmpty()) return state
        val (latitude, longitude) = reader.lastKnownLocation() ?: return state
        return state.copy(inside = state.inside + insideOf(latitude, longitude, fresh), baselined = state.baselined + fresh.map { it.name })
    }

    /**
     * Still on Wi-Fi with the name now hidden (location turned off or taken away) is the same network, not one left and
     * another joined: unknown means unchanged, as it does for charging.
     */
    private fun wifiKeepingName(saved: String?): String? {
        val now = reader.wifi()
        return if (now == "" && !saved.isNullOrEmpty()) saved else now
    }
}
