package app.whittle.automations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which side of a change an event is: arriving or leaving a place, plugging in or out, joining or leaving a network. */
@Serializable
enum class Edge { START, END }

/** The kinds of thing that happen on the phone and can start an automation. */
enum class EventKind { SMS, NOTIFICATION, PLACE, CHARGING, WIFI }

/**
 * Something that happened on the phone. [source] is who or where it came from (a sender, an app package, a place
 * or network name); [text] is what it said, with any one-time code already masked. [edge] is set for the kinds
 * that have one.
 */
@Serializable
data class PhoneEvent(
    val kind: EventKind,
    val source: String,
    val text: String,
    val atMillis: Long,
    val edge: Edge? = null,
)

/** What starts an automation when it isn't a time of day: an interval, or a [PhoneEvent] that passes the filters. */
@Serializable
sealed interface Trigger {
    /** Every [minutes] minutes after the last run. */
    @Serializable
    @SerialName("every")
    data class Every(val minutes: Int) : Trigger

    /** A text from any of [senders] (a sender contains one of them, ignoring case) with any of [words]; empty means any. */
    @Serializable
    @SerialName("sms")
    data class Sms(val senders: List<String> = emptyList(), val words: List<String> = emptyList()) : Trigger

    /** A notification from [app] (its package; [appLabel] is what the user sees) with any of [words]; empty means any. */
    @Serializable
    @SerialName("notification")
    data class Notification(val app: String, val appLabel: String, val words: List<String> = emptyList()) : Trigger

    /** Arriving at or leaving the place [name] within [radiusMeters] of the point. */
    @Serializable
    @SerialName("place")
    data class Place(val name: String, val latitude: Double, val longitude: Double, val radiusMeters: Int, val edge: Edge) : Trigger

    @Serializable
    @SerialName("charging")
    data class Charging(val edge: Edge) : Trigger

    /** Joining or leaving the network called [network], or any network when null. */
    @Serializable
    @SerialName("wifi")
    data class Wifi(val network: String?, val edge: Edge) : Trigger
}

/** Whether [event] is one this trigger starts. An interval never matches an event; it has alarms. */
fun Trigger.matches(event: PhoneEvent): Boolean = when (this) {
    is Trigger.Every -> false
    is Trigger.Sms -> event.kind == EventKind.SMS &&
        (senders.isEmpty() || senders.any { event.source.contains(it, ignoreCase = true) }) &&
        hasAnyWord(words, event.text)
    is Trigger.Notification -> event.kind == EventKind.NOTIFICATION && event.source == app && hasAnyWord(words, event.text)
    is Trigger.Place -> event.kind == EventKind.PLACE && event.source == name && event.edge == edge
    is Trigger.Charging -> event.kind == EventKind.CHARGING && event.edge == edge
    is Trigger.Wifi -> event.kind == EventKind.WIFI && event.edge == edge &&
        (network == null || event.source.equals(network, ignoreCase = true))
}

private fun hasAnyWord(words: List<String>, text: String): Boolean = words.isEmpty() || words.any { text.contains(it, ignoreCase = true) }
