package app.whittle.automations

import kotlinx.serialization.Serializable

/** The kinds of thing that happen on the phone and can start an automation. */
enum class EventKind { SMS, NOTIFICATION, PLACE, CHARGING, WIFI }

/**
 * Something that happened on the phone. [source] is who or where it came from (a sender, an app package, a place
 * or network name); [text] is what it said (an event that seemed to hold a one-time code is never kept). [edge] is set for the kinds
 * that have one. [sourceLabel] is what the user knows [source] as when that differs (an app's name for its package).
 */
@Serializable
data class PhoneEvent(
    val kind: EventKind,
    val source: String,
    val text: String,
    val atMillis: Long,
    val edge: Edge? = null,
    val sourceLabel: String = "",
)

/**
 * Whether [event] is one this trigger starts. An interval never matches an event; it has alarms. A text's sender is
 * matched with [matchesSender], [suffixDigits] deciding how much of a number must be given.
 */
fun Trigger.matches(event: PhoneEvent, suffixDigits: Int): Boolean = when (this) {
    is Trigger.Every -> false
    is Trigger.Sms -> event.kind == EventKind.SMS &&
        (senders.isEmpty() || senders.any { matchesSender(event.source, it, suffixDigits) }) &&
        hasAnyWord(words, event.text)
    is Trigger.Notification -> event.kind == EventKind.NOTIFICATION && event.source == app && hasAnyWord(words, event.text)
    is Trigger.Place -> event.kind == EventKind.PLACE && event.source == name && event.edge == edge
    is Trigger.Charging -> event.kind == EventKind.CHARGING && event.edge == edge
    is Trigger.Wifi -> event.kind == EventKind.WIFI && event.edge == edge &&
        (network == null || event.source.equals(network, ignoreCase = true))
}

/**
 * Whether a text from [sender] (as the phone reports it) is from [wanted] (as the user typed it). Never a substring, so
 * a stranger's number can't contain a bank's and "HDFCBK" isn't "FAKEHDFCBK":
 * - a number is compared digit by digit, ignoring spaces, dashes and brackets. One of at least [suffixDigits] digits
 *   may leave out the country code ("98765 43210" is "+919876543210"); a shorter one (a short code) must be all of it.
 * - a sender ID is compared whole, ignoring case, or as one of its dash-separated parts: operators put a prefix and
 *   sometimes a suffix around a registered ID, so "HDFCBK" is "VM-HDFCBK" and "AD-HDFCBK-S".
 */
fun matchesSender(sender: String, wanted: String, suffixDigits: Int): Boolean {
    val want = wanted.trim()
    if (want.isEmpty()) return false
    if (NUMBER.matches(want)) {
        val wantDigits = want.filter(Char::isDigit)
        if (!NUMBER.matches(sender.trim())) return false
        val digits = sender.filter(Char::isDigit)
        return if (wantDigits.length >= suffixDigits) digits.endsWith(wantDigits) else digits == wantDigits
    }
    val id = want.uppercase()
    val from = sender.trim().uppercase()
    return from == id || from.split('-').any { it == id }
}

private val NUMBER = Regex("^\\+?[\\d\\s()-]*\\d[\\d\\s()-]*$")

private fun hasAnyWord(words: List<String>, text: String): Boolean = words.isEmpty() || words.any { text.contains(it, ignoreCase = true) }
