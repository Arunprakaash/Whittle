package app.whittle.automations.ui

import androidx.compose.runtime.Composable
import app.whittle.automations.Edge
import app.whittle.automations.Trigger
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.trigger_every
import app.whittle.settings.ui.resources.trigger_notification
import app.whittle.settings.ui.resources.trigger_or
import app.whittle.settings.ui.resources.trigger_place_arrive
import app.whittle.settings.ui.resources.trigger_place_leave
import app.whittle.settings.ui.resources.trigger_plug_in
import app.whittle.settings.ui.resources.trigger_sms_any
import app.whittle.settings.ui.resources.trigger_sms_from
import app.whittle.settings.ui.resources.trigger_unplug
import app.whittle.settings.ui.resources.trigger_wifi_join
import app.whittle.settings.ui.resources.trigger_wifi_join_any
import app.whittle.settings.ui.resources.trigger_wifi_leave
import app.whittle.settings.ui.resources.trigger_wifi_leave_any
import app.whittle.settings.ui.resources.trigger_with_words
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The wording [describe] uses, passed in so the sentence logic stays plain Kotlin that a test can check. The pages
 * build it from the strings with [describeTrigger].
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

/** What [trigger] does, in a sentence, in the user's language. */
@Composable
fun describeTrigger(trigger: Trigger): String {
    // A plural needs its count while composing, and only an interval has one.
    val every = (trigger as? Trigger.Every)?.minutes?.let { pluralStringResource(Res.plurals.trigger_every, it, it) }.orEmpty()
    val smsFrom = stringResource(Res.string.trigger_sms_from)
    val notification = stringResource(Res.string.trigger_notification)
    val placeArrive = stringResource(Res.string.trigger_place_arrive)
    val placeLeave = stringResource(Res.string.trigger_place_leave)
    val wifiJoin = stringResource(Res.string.trigger_wifi_join)
    val wifiLeave = stringResource(Res.string.trigger_wifi_leave)
    val withWords = stringResource(Res.string.trigger_with_words)
    return describe(
        trigger,
        TriggerStrings(
            every = { every },
            smsAny = stringResource(Res.string.trigger_sms_any),
            smsFrom = { fill(smsFrom, it) },
            notification = { fill(notification, it) },
            placeArrive = { fill(placeArrive, it) },
            placeLeave = { fill(placeLeave, it) },
            plugIn = stringResource(Res.string.trigger_plug_in),
            unplug = stringResource(Res.string.trigger_unplug),
            wifiJoinAny = stringResource(Res.string.trigger_wifi_join_any),
            wifiLeaveAny = stringResource(Res.string.trigger_wifi_leave_any),
            wifiJoin = { fill(wifiJoin, it) },
            wifiLeave = { fill(wifiLeave, it) },
            withWords = { sentence, words -> fill(withWords, sentence, words) },
            or = stringResource(Res.string.trigger_or),
        ),
    )
}

/** What a trigger does, in a sentence: "When a text comes from HDFCBK, mentioning debited or credited". */
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

/** The icon for what starts an automation. */
fun triggerIcon(trigger: Trigger): String = when (trigger) {
    is Trigger.Every -> "repeat"
    is Trigger.Sms -> "sms"
    is Trigger.Notification -> "notifications"
    is Trigger.Place -> "location_on"
    is Trigger.Charging -> "battery_charging_full"
    is Trigger.Wifi -> "wifi"
}

private fun withWords(sentence: String, listed: List<String>, words: TriggerStrings) =
    if (listed.isEmpty()) sentence else words.withWords(sentence, readableList(listed, words.or))

private fun readableList(items: List<String>, or: String): String =
    if (items.size < 2) items.joinToString() else items.dropLast(1).joinToString(", ") + " $or " + items.last()

/** A string's `%1$s`, `%2$s` filled in order, for templates read once and filled for each trigger. */
private fun fill(template: String, vararg values: String): String =
    values.foldIndexed(template) { index, text, value -> text.replace("%${index + 1}\$s", value) }

/** The items of a comma list as typed: trimmed, blanks dropped, order kept. */
fun parseWords(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

/** [parseWords] the other way, to fill a field. */
fun listWords(items: List<String>): String = items.joinToString(", ")
