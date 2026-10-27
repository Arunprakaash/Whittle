package app.whittle.automations.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import app.whittle.automations.Edge
import app.whittle.automations.Trigger
import app.whittle.design.components.MenuAction
import app.whittle.design.components.SettingsChoiceRow
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.WhittleFilledField
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.WhittleSearchBar
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.window.userContent
import app.whittle.settings.ui.ButtonChoice
import app.whittle.settings.ui.ChoiceButtons
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

/** Which of the three kinds of "when" the editor is showing. */
enum class WhenMode { TIME, EVERY, EVENT }

/** The kinds of event the editor offers, each one [Trigger] type. */
enum class TriggerKind { SMS, NOTIFICATION, PLACE, CHARGING, WIFI }

/**
 * What the user has typed into the When section: all three kinds are kept while they switch between them, and
 * [trigger] is the one that's showing (null for a time), or [complete] is false while it can't be saved yet.
 */
@Stable
class WhenDraft(initial: AutomationEntry, private val limits: EventLimits) {
    private val start = initial.trigger

    var mode by mutableStateOf(
        when (start) {
            null -> WhenMode.TIME
            is Trigger.Every -> WhenMode.EVERY
            else -> WhenMode.EVENT
        },
    )
    var minuteOfDay by mutableIntStateOf(initial.minuteOfDay)
    var days by mutableStateOf(initial.days)
    var everyMinutes by mutableStateOf(((start as? Trigger.Every)?.minutes ?: limits.defaultEveryMinutes).toString())
    var kind by mutableStateOf(
        when (start) {
            is Trigger.Notification -> TriggerKind.NOTIFICATION
            is Trigger.Place -> TriggerKind.PLACE
            is Trigger.Charging -> TriggerKind.CHARGING
            is Trigger.Wifi -> TriggerKind.WIFI
            else -> TriggerKind.SMS
        },
    )
    var edge by mutableStateOf(
        when (start) {
            is Trigger.Place -> start.edge
            is Trigger.Charging -> start.edge
            is Trigger.Wifi -> start.edge
            else -> Edge.START
        },
    )
    var senders by mutableStateOf((start as? Trigger.Sms)?.senders?.let(::listWords).orEmpty())

    /** Kept from a recipe that acts on what a text says: such a text must come from someone named. */
    val senderRequired: Boolean = (start as? Trigger.Sms)?.senderRequired == true
    var words by mutableStateOf(
        when (start) {
            is Trigger.Sms -> listWords(start.words)
            is Trigger.Notification -> listWords(start.words)
            else -> ""
        },
    )
    var app by mutableStateOf((start as? Trigger.Notification)?.let { AppChoice(it.app, it.appLabel) })
    var placeName by mutableStateOf((start as? Trigger.Place)?.name.orEmpty())
    var location by mutableStateOf((start as? Trigger.Place)?.let { it.latitude to it.longitude })
    var radius by mutableStateOf(((start as? Trigger.Place)?.radiusMeters ?: limits.defaultPlaceRadiusMeters).toString())
    var network by mutableStateOf((start as? Trigger.Wifi)?.network.orEmpty())

    /** Once the user has had a say (or it was saved), the current network is no longer filled in for them. */
    var networkSettled by mutableStateOf(start is Trigger.Wifi)

    val radiusTooSmall: Boolean get() = radius.toIntOrNull()?.let { it < limits.minPlaceRadiusMeters } ?: true

    /** A text that may start a run that acts on it, from nobody named: the editor warns, and a recipe's can't be saved. */
    val sendersMissing: Boolean get() = mode == WhenMode.EVENT && kind == TriggerKind.SMS && parseWords(senders).isEmpty()

    val complete: Boolean get() = mode == WhenMode.TIME || trigger() != null

    /** The trigger this draft stands for; null for a time, or while it isn't complete. */
    fun trigger(): Trigger? = when (mode) {
        WhenMode.TIME -> null
        WhenMode.EVERY -> everyMinutes.toIntOrNull()?.takeIf { it > 0 }?.let { Trigger.Every(it) }
        WhenMode.EVENT -> eventTrigger()
    }

    private fun eventTrigger(): Trigger? = when (kind) {
        TriggerKind.SMS -> Trigger.Sms(parseWords(senders), parseWords(words), senderRequired)
        TriggerKind.NOTIFICATION -> app?.let { Trigger.Notification(it.packageName, it.label.ifBlank { it.packageName }, parseWords(words)) }
        TriggerKind.PLACE -> location?.takeIf { placeName.isNotBlank() && !radiusTooSmall }?.let { (latitude, longitude) ->
            Trigger.Place(placeName.trim(), latitude, longitude, radius.toInt(), edge)
        }
        TriggerKind.CHARGING -> Trigger.Charging(edge)
        TriggerKind.WIFI -> Trigger.Wifi(network.trim().ifEmpty { null }, edge)
    }
}

/** Time, Every or Event, as the settings' choice row. */
@Composable
internal fun WhenChoice(mode: WhenMode, enabled: Boolean, onChange: (WhenMode) -> Unit) {
    SettingsChoiceRow(icon = "schedule", title = stringResource(Res.string.automation_starts), shape = segmentedShape(0, 1), enabled = enabled) {
        val labels = listOf(Res.string.automation_when_time, Res.string.automation_when_every, Res.string.automation_when_event)
        ChoiceButtons(labels.map { ButtonChoice(stringResource(it)) }, WhenMode.entries.indexOf(mode), enabled) { onChange(WhenMode.entries[it]) }
    }
}

/** The minutes between runs. */
@Composable
internal fun EveryFields(draft: WhenDraft, limits: EventLimits, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
        WhittleFilledField(
            value = draft.everyMinutes,
            onValueChange = { draft.everyMinutes = it.filter(Char::isDigit).take(limits.maxNumberDigits) },
            label = stringResource(Res.string.automation_every_minutes),
            hint = pluralStringResource(Res.plurals.automation_every_hint, limits.minIntervalMinutes, limits.minIntervalMinutes),
            singleLine = true,
            enabled = enabled,
            keyboardType = KeyboardType.Number,
        )
        Summary(draft)
    }
}

/**
 * The kind of event, then what it needs to know, then the words for it. What only the phone can read (its apps, where it
 * is, its network) comes from [phone], and is asked for with [ask] first; on a computer neither is there.
 */
@Composable
internal fun EventFields(
    draft: WhenDraft,
    limits: EventLimits,
    enabled: Boolean,
    phone: PhoneReadings?,
    ask: ((List<String>, (Boolean) -> Unit) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var pickingKind by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<StringResource?>(null) }

    /** Runs [action] once what [type] needs is asked for; it runs either way, and reads what it can. */
    fun withAccess(type: String, action: () -> Unit) {
        val needed = limits.capabilities[type].orEmpty()
        if (ask == null || needed.isEmpty()) action() else ask(needed) { action() }
    }

    fun useCurrentNetwork(readings: PhoneReadings) {
        draft.networkSettled = true
        val name = readings.wifi()
        when {
            name == null -> hint = Res.string.automation_wifi_none
            name.isEmpty() -> hint = Res.string.automation_wifi_hidden
            else -> { draft.network = name; hint = null }
        }
    }
    // A new Wi-Fi trigger starts with the network the phone is on, when Android will say.
    LaunchedEffect(draft.kind, draft.mode) {
        if (draft.mode == WhenMode.EVENT && draft.kind == TriggerKind.WIFI && !draft.networkSettled && draft.network.isEmpty()) {
            draft.networkSettled = true
            phone?.wifi()?.takeIf { it.isNotEmpty() }?.let { draft.network = it }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
        Box {
            SettingsRow(
                icon = kindIcon(draft.kind),
                title = stringResource(kindLabel(draft.kind)),
                subtitle = stringResource(Res.string.automation_event_kind),
                shape = segmentedShape(0, 1),
                enabled = enabled,
                onClick = { pickingKind = true },
                trailing = { WhittleIcon("expand_more", size = WhittleSize.Icon.l, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            WhittleMenu(
                expanded = pickingKind,
                onDismiss = { pickingKind = false },
                groups = listOf(TriggerKind.entries.map { kind -> MenuAction(stringResource(kindLabel(kind)), kindIcon(kind), { draft.kind = kind; hint = null }) }),
            )
        }
        when (draft.kind) {
            TriggerKind.SMS -> {
                WhittleFilledField(
                    value = draft.senders,
                    onValueChange = { draft.senders = it },
                    label = stringResource(Res.string.automation_senders),
                    hint = stringResource(Res.string.automation_senders_hint),
                    // Anyone who sends the words could start it: a recipe that acts on a text needs someone named.
                    error = when {
                        !draft.sendersMissing -> null
                        draft.senderRequired -> stringResource(Res.string.automation_senders_needed)
                        else -> stringResource(Res.string.automation_senders_anyone)
                    },
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.userContent(),
                )
                WordsField(draft, enabled)
            }
            TriggerKind.NOTIFICATION -> {
                val app = draft.app
                SettingsRow(
                    icon = "apps",
                    title = app?.label ?: stringResource(if (phone != null) Res.string.automation_pick_app else Res.string.automation_pick_app_on_phone),
                    subtitle = app?.packageName,
                    shape = segmentedShape(0, 1),
                    enabled = enabled && phone != null,
                    onClick = { pickingApp = true },
                    modifier = Modifier.userContent(),
                    trailing = { WhittleIcon("expand_more", size = WhittleSize.Icon.l, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                WordsField(draft, enabled)
                SupportingNote("info", stringResource(Res.string.automation_app_note))
            }
            TriggerKind.PLACE -> {
                WhittleFilledField(
                    value = draft.placeName,
                    onValueChange = { draft.placeName = it.take(limits.maxPlaceNameChars) },
                    label = stringResource(Res.string.automation_place_name),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.userContent(),
                )
                val location = draft.location
                SettingsRow(
                    icon = "location_on",
                    title = stringResource(if (locating) Res.string.automation_place_locating else Res.string.automation_place_here),
                    subtitle = location?.let { (latitude, longitude) -> "${coordinate(latitude)}, ${coordinate(longitude)}" }
                        ?: stringResource(Res.string.automation_place_unset),
                    shape = segmentedShape(0, 1),
                    enabled = enabled && !locating && phone != null,
                    modifier = Modifier.userContent(),
                    onClick = {
                        val readings = phone ?: return@SettingsRow
                        hint = null
                        withAccess(Trigger.PLACE) {
                            locating = true
                            scope.launch {
                                val fix = readings.location()
                                if (fix == null) hint = Res.string.automation_place_error else draft.location = fix
                                locating = false
                            }
                        }
                    },
                )
                val minimum = pluralStringResource(Res.plurals.automation_radius_hint, limits.minPlaceRadiusMeters, limits.minPlaceRadiusMeters)
                WhittleFilledField(
                    value = draft.radius,
                    onValueChange = { draft.radius = it.filter(Char::isDigit).take(limits.maxNumberDigits) },
                    label = stringResource(Res.string.automation_radius),
                    hint = minimum,
                    error = minimum.takeIf { draft.radiusTooSmall },
                    singleLine = true,
                    enabled = enabled,
                    keyboardType = KeyboardType.Number,
                )
                EdgeChoice(draft, enabled, Res.string.automation_arrive, Res.string.automation_leave)
                if (limits.placePolling) LateNote(limits)
            }
            TriggerKind.CHARGING -> {
                EdgeChoice(draft, enabled, Res.string.automation_plug_in, Res.string.automation_unplug)
                if (draft.edge == Edge.END) LateNote(limits)
            }
            TriggerKind.WIFI -> {
                WhittleFilledField(
                    value = draft.network,
                    onValueChange = { draft.network = it; draft.networkSettled = true; hint = null },
                    label = stringResource(Res.string.automation_wifi_name),
                    hint = stringResource(Res.string.automation_wifi_hint),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.userContent(),
                )
                if (phone != null) {
                    SettingsRow(
                        icon = "wifi",
                        title = stringResource(Res.string.automation_wifi_here),
                        subtitle = null,
                        shape = segmentedShape(0, 1),
                        enabled = enabled,
                        onClick = { withAccess(Trigger.WIFI) { useCurrentNetwork(phone) } },
                    )
                }
                EdgeChoice(draft, enabled, Res.string.automation_wifi_join, Res.string.automation_wifi_leave)
                if (draft.edge == Edge.END) LateNote(limits)
            }
        }
        hint?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = WhittleSpace.s)) }
        Summary(draft)
    }
    if (pickingApp && phone != null) {
        AppPicker(phone, onDismiss = { pickingApp = false }) { draft.app = it; pickingApp = false }
    }
}

/**
 * What the saved automation still needs from the phone, as a row: it says so, and tapping it asks (on the phone; a
 * computer can only say where).
 */
@Composable
internal fun AccessRow(missing: List<String>, enabled: Boolean, ask: ((List<String>, (Boolean) -> Unit) -> Unit)?) {
    SettingsRow(
        icon = "error",
        title = stringResource(Res.string.automation_needs_access),
        subtitle = stringResource(if (ask != null) Res.string.automation_allow else Res.string.automation_allow_on_phone),
        shape = segmentedShape(0, 1),
        enabled = enabled && ask != null,
        destructive = true,
        onClick = ask?.let { request -> { request(missing) {} } },
    )
}

@Composable
private fun WordsField(draft: WhenDraft, enabled: Boolean) {
    WhittleFilledField(
        value = draft.words,
        onValueChange = { draft.words = it },
        label = stringResource(Res.string.automation_words),
        hint = stringResource(Res.string.automation_words_hint),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.userContent(),
    )
}

/** Which side of the change starts it (arrive or leave, plug in or unplug), as the settings' choice row. */
@Composable
private fun EdgeChoice(draft: WhenDraft, enabled: Boolean, start: StringResource, end: StringResource) {
    SettingsChoiceRow(icon = kindIcon(draft.kind), title = stringResource(Res.string.automation_edge), shape = segmentedShape(0, 1), enabled = enabled) {
        ChoiceButtons(listOf(ButtonChoice(stringResource(start)), ButtonChoice(stringResource(end))), if (draft.edge == Edge.START) 0 else 1, enabled) {
            draft.edge = if (it == 0) Edge.START else Edge.END
        }
    }
}

/** Why a change can be late: Android has nothing to wake Whittle for it except its periodic look. */
@Composable
private fun LateNote(limits: EventLimits) {
    SupportingNote("schedule", pluralStringResource(Res.plurals.automation_late_note, limits.placeCheckMinutes, limits.placeCheckMinutes))
}

/** The trigger in words, as the list will show it. */
@Composable
private fun Summary(draft: WhenDraft) {
    val trigger = draft.trigger() ?: return
    Text(
        describeTrigger(trigger),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = WhittleSpace.s).userContent(),
    )
}

/** The phone's apps with a launcher icon, searchable by name or package. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(phone: PhoneReadings, onDismiss: () -> Unit, onPick: (AppChoice) -> Unit) {
    val search = rememberTextFieldState()
    val apps by produceState(emptyList<AppChoice>(), phone) { value = phone.apps() }
    val query = search.text.toString().trim()
    val shown = apps.filter { query.isEmpty() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = WhittleSpace.l), verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Text(stringResource(Res.string.automation_pick_app), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = WhittleSpace.s))
            WhittleSearchBar(search, placeholder = stringResource(Res.string.automation_app_search), clearLabel = stringResource(Res.string.search_clear))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                itemsIndexed(shown, key = { _, app -> app.packageName }) { index, app ->
                    SettingsRow(
                        icon = "apps",
                        title = app.label,
                        subtitle = app.packageName,
                        shape = segmentedShape(index, shown.size),
                        onClick = { onPick(app) },
                        modifier = Modifier.userContent(),
                    )
                }
            }
        }
    }
}

private fun kindIcon(kind: TriggerKind) = when (kind) {
    TriggerKind.SMS -> "sms"
    TriggerKind.NOTIFICATION -> "notifications"
    TriggerKind.PLACE -> "location_on"
    TriggerKind.CHARGING -> "battery_charging_full"
    TriggerKind.WIFI -> "wifi"
}

private fun kindLabel(kind: TriggerKind) = when (kind) {
    TriggerKind.SMS -> Res.string.automation_event_sms
    TriggerKind.NOTIFICATION -> Res.string.automation_event_notification
    TriggerKind.PLACE -> Res.string.automation_event_place
    TriggerKind.CHARGING -> Res.string.automation_event_charging
    TriggerKind.WIFI -> Res.string.automation_event_wifi
}

/** A coordinate to four places, about ten metres: enough to tell the user a spot was taken. */
private fun coordinate(value: Double): String = ((value * COORDINATE_SCALE).roundToLong() / COORDINATE_SCALE).toString()

private const val COORDINATE_SCALE = 10_000.0
