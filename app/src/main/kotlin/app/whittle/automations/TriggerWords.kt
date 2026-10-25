package app.whittle.automations

import android.content.res.Resources
import app.whittle.R

/**
 * The wording [describe] uses, passed in so the sentence logic stays plain Kotlin that a test can check. The app
 * builds it from strings.xml with [triggerStrings].
 */
data class TriggerStrings(
    val every: (Int) -> String,
    val smsAny: String,
    val smsFrom: (String) -> String,
    val notification: (String) -> String,
    val placeArrive: (String) -> String,
    val placeLeave: (String) -> String,
    val plugIn: String,
    val unplug: String,
    val wifiJoinAny: String,
    val wifiLeaveAny: String,
    val wifiJoin: (String) -> String,
    val wifiLeave: (String) -> String,
    /** Adds the words a text or notification must contain to a sentence: the sentence, then the words as listed. */
    val withWords: (String, String) -> String,
    /** The word before the last item of a list: "A, B or C". */
    val or: String,
)

fun triggerStrings(resources: Resources) = TriggerStrings(
    every = { resources.getQuantityString(R.plurals.trigger_every, it, it) },
    smsAny = resources.getString(R.string.trigger_sms_any),
    smsFrom = { resources.getString(R.string.trigger_sms_from, it) },
    notification = { resources.getString(R.string.trigger_notification, it) },
    placeArrive = { resources.getString(R.string.trigger_place_arrive, it) },
    placeLeave = { resources.getString(R.string.trigger_place_leave, it) },
    plugIn = resources.getString(R.string.trigger_plug_in),
    unplug = resources.getString(R.string.trigger_unplug),
    wifiJoinAny = resources.getString(R.string.trigger_wifi_join_any),
    wifiLeaveAny = resources.getString(R.string.trigger_wifi_leave_any),
    wifiJoin = { resources.getString(R.string.trigger_wifi_join, it) },
    wifiLeave = { resources.getString(R.string.trigger_wifi_leave, it) },
    withWords = { sentence, words -> resources.getString(R.string.trigger_with_words, sentence, words) },
    or = resources.getString(R.string.trigger_or),
)

/** What a trigger does, in a sentence: "When a text comes from HDFCBK, saying debited or credited". */
fun describe(trigger: Trigger, words: TriggerStrings): String = when (trigger) {
    is Trigger.Every -> words.every(trigger.minutes)
    is Trigger.Sms -> withWords(
        if (trigger.senders.isEmpty()) words.smsAny else words.smsFrom(readableList(trigger.senders, words.or)),
        trigger.words,
        words,
    )
    is Trigger.Notification -> withWords(words.notification(trigger.appLabel), trigger.words, words)
    is Trigger.Place -> if (trigger.edge == Edge.START) words.placeArrive(trigger.name) else words.placeLeave(trigger.name)
    is Trigger.Charging -> if (trigger.edge == Edge.START) words.plugIn else words.unplug
    is Trigger.Wifi -> when {
        trigger.network == null -> if (trigger.edge == Edge.START) words.wifiJoinAny else words.wifiLeaveAny
        trigger.edge == Edge.START -> words.wifiJoin(trigger.network)
        else -> words.wifiLeave(trigger.network)
    }
}

private fun withWords(sentence: String, listed: List<String>, words: TriggerStrings) =
    if (listed.isEmpty()) sentence else words.withWords(sentence, readableList(listed, words.or))

private fun readableList(items: List<String>, or: String): String =
    if (items.size < 2) items.joinToString() else items.dropLast(1).joinToString(", ") + " $or " + items.last()

/** The items of a comma list as typed: trimmed, blanks dropped, order kept. */
fun parseWords(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** [parseWords] the other way, to fill a field. */
fun listWords(items: List<String>): String = items.joinToString(", ")
