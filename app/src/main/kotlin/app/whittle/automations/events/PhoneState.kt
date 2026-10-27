package app.whittle.automations.events

import app.whittle.automations.Edge
import app.whittle.automations.EventKind
import app.whittle.automations.PhoneEvent
import app.whittle.automations.Trigger
import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the phone was last seen doing, kept so the next look can tell what changed. A null [charging] or [wifi] is
 * unknown or not connected; [inside] holds the names of the places the phone is within the radius of. [baselined]
 * holds the places whose [inside] was set from a first look at where the phone is, so an old file reads as none.
 */
@Serializable
data class PhoneState(val charging: Boolean?, val wifi: String?, val inside: Set<String>, val baselined: Set<String> = emptySet())

/**
 * What the enabled automations ask the phone to watch; nothing is armed for a source that is false or empty.
 * [unplugging] and [leavingWifi] are the edges Android has no wake for, which only a periodic look catches.
 */
data class PhoneNeeds(
    val charging: Boolean = false,
    val wifi: Boolean = false,
    val places: List<Trigger.Place> = emptyList(),
    val unplugging: Boolean = false,
    val leavingWifi: Boolean = false,
) {
    val any: Boolean get() = charging || wifi || places.isNotEmpty()

    /**
     * Whether the phone must be looked at every so often: for an edge nothing wakes Whittle for, for a plug-in while
     * already plugged in (the charger job waits for a plug-in, so only a look sees the unplug that arms it again), and
     * for places when they're polled instead of left to proximity alerts.
     */
    fun needsPeriodicLook(chargingNow: Boolean?, placePolling: Boolean): Boolean =
        unplugging || leavingWifi || (charging && chargingNow == true) || (placePolling && places.isNotEmpty())
}

/** The triggers of the enabled automations, reduced to what needs watching. A place counts once however many edges use it. */
fun needsOf(triggers: List<Trigger>): PhoneNeeds = PhoneNeeds(
    charging = triggers.any { it is Trigger.Charging },
    wifi = triggers.any { it is Trigger.Wifi },
    places = triggers.filterIsInstance<Trigger.Place>().distinctBy { Triple(it.name, it.latitude, it.longitude) },
    unplugging = triggers.any { it is Trigger.Charging && it.edge == Edge.END },
    leavingWifi = triggers.any { it is Trigger.Wifi && it.edge == Edge.END },
)

/** The events between two looks at the phone. Unknown charging gives no edge; a place that no longer exists gives none. */
fun edges(before: PhoneState, after: PhoneState, places: List<Trigger.Place>, now: Long): List<PhoneEvent> = buildList {
    fun event(kind: EventKind, source: String, edge: Edge) = PhoneEvent(kind, source, "", now, edge)
    if (before.charging != null && after.charging != null && before.charging != after.charging) {
        add(event(EventKind.CHARGING, "", if (after.charging) Edge.START else Edge.END))
    }
    if (before.wifi != after.wifi) {
        after.wifi?.let { add(event(EventKind.WIFI, it, Edge.START)) }
        before.wifi?.let { add(event(EventKind.WIFI, it, Edge.END)) }
    }
    val known = places.map { it.name }.toSet()
    (after.inside - before.inside).filter { it in known }.forEach { add(event(EventKind.PLACE, it, Edge.START)) }
    (before.inside - after.inside).filter { it in known }.forEach { add(event(EventKind.PLACE, it, Edge.END)) }
}

/** The names of the [places] a point is within the radius of. */
fun insideOf(latitude: Double, longitude: Double, places: List<Trigger.Place>): Set<String> =
    places.filter { distanceMeters(latitude, longitude, it.latitude, it.longitude) <= it.radiusMeters }.map { it.name }.toSet()

/** The haversine distance between two points on the earth, in meters. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
