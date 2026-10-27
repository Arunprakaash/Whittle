package app.whittle.automations

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Which side of a change an event is: arriving or leaving a place, plugging in or out, joining or leaving a network. */
@Serializable
enum class Edge { START, END }

/**
 * What starts an automation when it isn't a time of day: an interval, or something that happens on the phone. Shared
 * by the phone, which stores and runs it, and the Automations pages, which edit it on the phone or a computer.
 */
@Serializable
sealed interface Trigger {
    /** Every [minutes] minutes after the last run. */
    @Serializable
    @SerialName(EVERY)
    data class Every(val minutes: Int) : Trigger

    /**
     * A text from any of [senders] with any of [words]; empty means any. A sender is a number or a sender ID as the
     * phone shows it, matched whole (see the phone's `matchesSender`). [senderRequired] is set by recipes that act on
     * what a text says (a bank's), which mustn't be started by anyone who sends the right words.
     */
    @Serializable
    @SerialName(SMS)
    data class Sms(
        val senders: List<String> = emptyList(),
        val words: List<String> = emptyList(),
        val senderRequired: Boolean = false,
    ) : Trigger

    /** A notification from [app] (its package; [appLabel] is what the user sees) with any of [words]; empty means any. */
    @Serializable
    @SerialName(NOTIFICATION)
    data class Notification(val app: String, val appLabel: String, val words: List<String> = emptyList()) : Trigger

    /** Arriving at or leaving the place [name] within [radiusMeters] of the point. */
    @Serializable
    @SerialName(PLACE)
    data class Place(val name: String, val latitude: Double, val longitude: Double, val radiusMeters: Int, val edge: Edge) : Trigger

    @Serializable
    @SerialName(CHARGING)
    data class Charging(val edge: Edge) : Trigger

    /** Joining or leaving the network called [network], or any network when null. */
    @Serializable
    @SerialName(WIFI)
    data class Wifi(val network: String?, val edge: Edge) : Trigger

    companion object {
        const val EVERY = "every"
        const val SMS = "sms"
        const val NOTIFICATION = "notification"
        const val PLACE = "place"
        const val CHARGING = "charging"
        const val WIFI = "wifi"
    }
}

/**
 * Started by what someone else wrote (a text, a notification), so its runs read untrusted content and may only read.
 * Places, charging and Wi-Fi carry nothing anyone can write.
 */
val Trigger.readsMessages: Boolean get() = this is Trigger.Sms || this is Trigger.Notification

/**
 * What the phone must grant before this trigger can work, from [byType]: automations.json's events.capabilities, by the
 * trigger's type. Wi-Fi needs location only for a network's name; any network needs nothing.
 */
fun Trigger.capabilities(byType: Map<String, List<String>>): List<String> = when (this) {
    is Trigger.Every -> byType[Trigger.EVERY].orEmpty()
    is Trigger.Sms -> byType[Trigger.SMS].orEmpty()
    is Trigger.Notification -> byType[Trigger.NOTIFICATION].orEmpty()
    is Trigger.Place -> byType[Trigger.PLACE].orEmpty()
    is Trigger.Charging -> byType[Trigger.CHARGING].orEmpty()
    is Trigger.Wifi -> if (network == null) emptyList() else byType[Trigger.WIFI].orEmpty()
}
