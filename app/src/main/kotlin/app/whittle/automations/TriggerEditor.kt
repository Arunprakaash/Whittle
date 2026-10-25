package app.whittle.automations

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.WhittleApplication
import app.whittle.automations.events.AndroidPhoneReader
import app.whittle.design.components.MenuAction
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.WhittleFilledField
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.WhittleSearchBar
import app.whittle.design.components.connectedShape
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.observability.posthog.userContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the three kinds of "when" the editor is showing. */
enum class WhenMode { TIME, EVERY, EVENT }

/**
 * What the user has typed into the When section: all three kinds are kept while they switch between them, and
 * [value] is the one that's showing, or null while it isn't complete.
 */
@Stable
class WhenDraft(initial: Automation, config: AutomationsConfig) {
    private val trigger = initial.trigger

    var mode by mutableStateOf(
        when {
            trigger == null -> WhenMode.TIME
            trigger is Trigger.Every -> WhenMode.EVERY
            else -> WhenMode.EVENT
        },
    )
    var minuteOfDay by mutableIntStateOf(initial.schedule?.minuteOfDay ?: config.defaultMinuteOfDay)
    var days by mutableStateOf(initial.schedule?.days ?: Schedule.EVERY_DAY)
    var everyMinutes by mutableStateOf(((trigger as? Trigger.Every)?.minutes ?: DEFAULT_EVERY_MINUTES.coerceAtLeast(config.events.minIntervalMinutes)).toString())
    var kind by mutableStateOf(
        when (trigger) {
            is Trigger.Notification -> EventKind.NOTIFICATION
            is Trigger.Place -> EventKind.PLACE
            is Trigger.Charging -> EventKind.CHARGING
            is Trigger.Wifi -> EventKind.WIFI
            else -> EventKind.SMS
        },
    )
    var edge by mutableStateOf(
        when (trigger) {
            is Trigger.Place -> trigger.edge
            is Trigger.Charging -> trigger.edge
            is Trigger.Wifi -> trigger.edge
            else -> Edge.START
        },
    )
    var senders by mutableStateOf((trigger as? Trigger.Sms)?.senders?.let(::listWords).orEmpty())
    var words by mutableStateOf(
        when (trigger) {
            is Trigger.Sms -> listWords(trigger.words)
            is Trigger.Notification -> listWords(trigger.words)
            else -> ""
        },
    )
    var app by mutableStateOf((trigger as? Trigger.Notification)?.app)
    var appLabel by mutableStateOf((trigger as? Trigger.Notification)?.appLabel.orEmpty())
    var placeName by mutableStateOf((trigger as? Trigger.Place)?.name.orEmpty())
    var latitude by mutableStateOf((trigger as? Trigger.Place)?.latitude)
    var longitude by mutableStateOf((trigger as? Trigger.Place)?.longitude)
    var radius by mutableStateOf(((trigger as? Trigger.Place)?.radiusMeters ?: config.events.defaultPlaceRadiusMeters).toString())
    var network by mutableStateOf((trigger as? Trigger.Wifi)?.network.orEmpty())

    /** Once the user has had a say (or it was saved), the current network is no longer filled in for them. */
    var networkSettled by mutableStateOf(trigger is Trigger.Wifi)

    private val minRadius = config.events.minPlaceRadiusMeters

    val radiusTooSmall: Boolean get() = radius.toIntOrNull()?.let { it < minRadius } ?: true

    /** The time and the trigger that this draft stands for, one of them null; null while the draft isn't complete. */
    fun value(): Pair<Schedule?, Trigger?>? = when (mode) {
        WhenMode.TIME -> Schedule(minuteOfDay, days) to null
        WhenMode.EVERY -> everyMinutes.toIntOrNull()?.takeIf { it > 0 }?.let { null to Trigger.Every(it) }
        WhenMode.EVENT -> eventTrigger()?.let { null to it }
    }

    private fun eventTrigger(): Trigger? = when (kind) {
        EventKind.SMS -> Trigger.Sms(parseWords(senders), parseWords(words))
        EventKind.NOTIFICATION -> app?.let { Trigger.Notification(it, appLabel.ifBlank { it }, parseWords(words)) }
        EventKind.PLACE -> {
            val lat = latitude
            val lon = longitude
            if (placeName.isBlank() || lat == null || lon == null || radiusTooSmall) null else Trigger.Place(placeName.trim(), lat, lon, radius.toInt(), edge)
        }
        EventKind.CHARGING -> Trigger.Charging(edge)
        EventKind.WIFI -> Trigger.Wifi(network.trim().ifEmpty { null }, edge)
    }

    private companion object {
        const val DEFAULT_EVERY_MINUTES = 60
    }
}

/** The icon for what starts an automation. */
internal fun triggerIcon(trigger: Trigger): String = when (trigger) {
    is Trigger.Every -> "repeat"
    is Trigger.Sms -> "sms"
    is Trigger.Notification -> "notifications"
    is Trigger.Place -> "location_on"
    is Trigger.Charging -> "battery_charging_full"
    is Trigger.Wifi -> "wifi"
}

private fun kindIcon(kind: EventKind) = when (kind) {
    EventKind.SMS -> "sms"
    EventKind.NOTIFICATION -> "notifications"
    EventKind.PLACE -> "location_on"
    EventKind.CHARGING -> "battery_charging_full"
    EventKind.WIFI -> "wifi"
}

/**
 * Counts up each time the user comes back to the app or answers a permission prompt, so what's granted is read again.
 * Whenever it does, the phone watch arms again, so a source that was skipped for a missing permission registers now.
 */
@Composable
fun rememberPermissionChecks(graph: AppGraph): MutableIntState {
    val context = LocalContext.current
    val checks = remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks.intValue++ }
    LaunchedEffect(checks.intValue) {
        graph.appScope.launch { (context.applicationContext as WhittleApplication).phoneStateWatch.arm() }
    }
    return checks
}

/** Time, Every or Event, as three joined buttons. */
@Composable
fun WhenModes(mode: WhenMode, enabled: Boolean, onChange: (WhenMode) -> Unit) {
    val labels = listOf(R.string.automation_when_time, R.string.automation_when_every, R.string.automation_when_event)
    Choices(labels.map { stringResource(it) }, WhenMode.entries.indexOf(mode), enabled) { onChange(WhenMode.entries[it]) }
}

/** A few choices as joined buttons; the chosen one is filled. */
@Composable
private fun Choices(labels: List<String>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Button(
                onClick = { haptics.perform(WhittleHaptic.Select); onSelect(index) },
                enabled = enabled,
                shape = connectedShape(index, labels.size),
                colors = if (on) {
                    ButtonDefaults.buttonColors()
                } else {
                    ButtonDefaults.buttonColors(containerColor = colors.surfaceContainerHigh, contentColor = colors.onSurface)
                },
                modifier = Modifier.weight(1f).height(WhittleSize.Button.default),
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** The minutes between runs. */
@Composable
fun EveryFields(draft: WhenDraft, config: AutomationsConfig, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
        WhittleFilledField(
            value = draft.everyMinutes,
            onValueChange = { draft.everyMinutes = it.filter(Char::isDigit).take(MAX_DIGITS) },
            label = stringResource(R.string.automation_every_minutes),
            hint = pluralStringResource(R.plurals.automation_every_hint, config.events.minIntervalMinutes, config.events.minIntervalMinutes),
            singleLine = true,
            enabled = enabled,
            keyboardType = KeyboardType.Number,
        )
        Summary(draft)
    }
}

/** The kind of event, then what it needs to know, then the words for it. */
@Composable
fun EventFields(draft: WhenDraft, config: AutomationsConfig, enabled: Boolean, checks: MutableIntState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickingKind by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var afterLocation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        checks.intValue++
        if (result.values.any { it }) afterLocation?.invoke()
        afterLocation = null
    }

    /** Runs [action] once location is allowed, asking first when it isn't. */
    fun withLocation(action: () -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterLocation = action
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    val placeError = stringResource(R.string.automation_place_error)
    val noWifi = stringResource(R.string.automation_wifi_none)
    val hiddenWifi = stringResource(R.string.automation_wifi_hidden)

    fun useCurrentNetwork() {
        draft.networkSettled = true
        val name = AndroidPhoneReader(context).wifi()
        when {
            name == null -> hint = noWifi
            name.isEmpty() -> hint = hiddenWifi
            else -> { draft.network = name; hint = null }
        }
    }
    // A new Wi-Fi trigger starts with the network the phone is on, when Android will say.
    LaunchedEffect(draft.kind, draft.mode) {
        if (draft.mode == WhenMode.EVENT && draft.kind == EventKind.WIFI && !draft.networkSettled && draft.network.isEmpty()) {
            draft.networkSettled = true
            AndroidPhoneReader(context).wifi()?.takeIf { it.isNotEmpty() }?.let { draft.network = it }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
        val kinds = listOf(
            EventKind.SMS to R.string.automation_event_sms,
            EventKind.NOTIFICATION to R.string.automation_event_notification,
            EventKind.PLACE to R.string.automation_event_place,
            EventKind.CHARGING to R.string.automation_event_charging,
            EventKind.WIFI to R.string.automation_event_wifi,
        )
        Box {
            SettingsRow(
                icon = kindIcon(draft.kind),
                title = stringResource(kinds.first { it.first == draft.kind }.second),
                subtitle = stringResource(R.string.automation_event_kind),
                shape = segmentedShape(0, 1),
                enabled = enabled,
                onClick = { pickingKind = true },
                trailing = { WhittleIcon("expand_more", size = WhittleSize.Icon.l, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
            WhittleMenu(
                expanded = pickingKind,
                onDismiss = { pickingKind = false },
                groups = listOf(kinds.map { (kind, label) -> MenuAction(stringResource(label), kindIcon(kind), { draft.kind = kind; hint = null }) }),
            )
        }
        when (draft.kind) {
            EventKind.SMS -> {
                WhittleFilledField(
                    value = draft.senders,
                    onValueChange = { draft.senders = it },
                    label = stringResource(R.string.automation_senders),
                    hint = stringResource(R.string.automation_senders_hint),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.userContent(),
                )
                WordsField(draft, enabled)
            }
            EventKind.NOTIFICATION -> {
                SettingsRow(
                    icon = "apps",
                    title = draft.app?.let { draft.appLabel.ifBlank { it } } ?: stringResource(R.string.automation_pick_app),
                    subtitle = draft.app,
                    shape = segmentedShape(0, 1),
                    enabled = enabled,
                    onClick = { pickingApp = true },
                    trailing = { WhittleIcon("expand_more", size = WhittleSize.Icon.l, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                WordsField(draft, enabled)
                SupportingNote("info", stringResource(R.string.automation_app_note))
            }
            EventKind.PLACE -> {
                WhittleFilledField(
                    value = draft.placeName,
                    onValueChange = { draft.placeName = it.take(MAX_PLACE_NAME) },
                    label = stringResource(R.string.automation_place_name),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.userContent(),
                )
                val lat = draft.latitude
                val lon = draft.longitude
                SettingsRow(
                    icon = "location_on",
                    title = stringResource(if (locating) R.string.automation_place_locating else R.string.automation_place_here),
                    subtitle = if (lat != null && lon != null) stringResource(R.string.automation_place_set, lat, lon) else null,
                    shape = segmentedShape(0, 1),
                    enabled = enabled && !locating,
                    onClick = {
                        hint = null
                        withLocation {
                            locating = true
                            scope.launch {
                                val fix = AndroidPhoneReader(context).location()
                                if (fix == null) hint = placeError else { draft.latitude = fix.first; draft.longitude = fix.second }
                                locating = false
                            }
                        }
                    },
                )
                WhittleFilledField(
                    value = draft.radius,
                    onValueChange = { draft.radius = it.filter(Char::isDigit).take(MAX_DIGITS) },
                    label = stringResource(R.string.automation_radius),
                    hint = pluralStringResource(R.plurals.automation_radius_hint, config.events.minPlaceRadiusMeters, config.events.minPlaceRadiusMeters),
                    error = if (draft.radiusTooSmall) pluralStringResource(R.plurals.automation_radius_hint, config.events.minPlaceRadiusMeters, config.events.minPlaceRadiusMeters) else null,
                    singleLine = true,
                    enabled = enabled,
                    keyboardType = KeyboardType.Number,
                )
                EdgeChoices(draft, enabled, R.string.automation_arrive, R.string.automation_leave)
                if (config.events.placePolling) LateNote(config)
            }
            EventKind.CHARGING -> {
                EdgeChoices(draft, enabled, R.string.automation_plug_in, R.string.automation_unplug)
                if (draft.edge == Edge.END) LateNote(config)
            }
            EventKind.WIFI -> {
                WhittleFilledField(
                    value = draft.network,
                    onValueChange = { draft.network = it; draft.networkSettled = true; hint = null },
                    label = stringResource(R.string.automation_wifi_name),
                    hint = stringResource(R.string.automation_wifi_hint),
                    singleLine = true,
                    enabled = enabled,
                    modifier = Modifier.userContent(),
                )
                SettingsRow(
                    icon = "wifi",
                    title = stringResource(R.string.automation_wifi_here),
                    subtitle = null,
                    shape = segmentedShape(0, 1),
                    enabled = enabled,
                    onClick = { withLocation { useCurrentNetwork() } },
                )
                EdgeChoices(draft, enabled, R.string.automation_wifi_join, R.string.automation_wifi_leave)
                if (draft.edge == Edge.END) LateNote(config)
            }
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = WhittleSpace.s)) }
        Summary(draft)
    }
    if (pickingApp) {
        AppPicker(onDismiss = { pickingApp = false }) { packageName, label -> draft.app = packageName; draft.appLabel = label; pickingApp = false }
    }
}

@Composable
private fun WordsField(draft: WhenDraft, enabled: Boolean) {
    WhittleFilledField(
        value = draft.words,
        onValueChange = { draft.words = it },
        label = stringResource(R.string.automation_words),
        hint = stringResource(R.string.automation_words_hint),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.userContent(),
    )
}

@Composable
private fun EdgeChoices(draft: WhenDraft, enabled: Boolean, start: Int, end: Int) {
    Choices(listOf(stringResource(start), stringResource(end)), if (draft.edge == Edge.START) 0 else 1, enabled) {
        draft.edge = if (it == 0) Edge.START else Edge.END
    }
}

/** Why a leaving can be late: Android has nothing to wake Whittle for it except its periodic check. */
@Composable
private fun LateNote(config: AutomationsConfig) {
    SupportingNote("schedule", pluralStringResource(R.plurals.automation_late_note, config.events.placeCheckMinutes, config.events.placeCheckMinutes))
}

/** The trigger in words, as the list will show it. */
@Composable
private fun Summary(draft: WhenDraft) {
    val words = triggerStrings(LocalResources.current)
    val trigger = draft.value()?.second ?: return
    Text(
        describe(trigger, words),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = WhittleSpace.s).userContent(),
    )
}

/** The apps that have a launcher icon, searchable by name or package; picking one returns its package and name. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    val search = rememberTextFieldState()
    val apps by produceState(emptyList<Pair<String, String>>()) { value = withContext(Dispatchers.Default) { launchableApps(context) } }
    val query = search.text.toString().trim()
    val shown = apps.filter { query.isEmpty() || it.second.contains(query, ignoreCase = true) || it.first.contains(query, ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = WhittleSpace.l), verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Text(stringResource(R.string.automation_pick_app), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = WhittleSpace.s))
            WhittleSearchBar(search, placeholder = stringResource(R.string.automation_app_search), clearLabel = stringResource(R.string.search_clear))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                items(shown, key = { it.first }) { (packageName, label) ->
                    SettingsRow(
                        icon = "apps",
                        title = label,
                        subtitle = packageName,
                        shape = RoundedCornerShape(WhittleSpace.s),
                        onClick = { onPick(packageName, label) },
                    )
                }
            }
        }
    }
}

/** Package and label of every app with a launcher entry, by label. Before Android 11 hides packages, which at target 28 it doesn't. */
private fun launchableApps(context: Context): List<Pair<String, String>> {
    val packages = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packages.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.packageName to it.loadLabel(packages).toString() }
        .distinctBy { it.first }
        .sortedBy { it.second.lowercase() }
}

/**
 * After saving: one line for each thing the automation still needs, with a button that asks for it. Never blocks
 * saving; the automation simply waits. Where Android may call a setting restricted, says how to allow it.
 */
@Composable
fun PermissionSteps(
    missing: List<TriggerPermission>,
    checks: MutableIntState,
    onNotNow: () -> Unit,
) {
    val context = LocalContext.current
    val attempts = remember { mutableMapOf<TriggerPermission, Int>() }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checks.intValue++ }

    fun grant(permission: TriggerPermission) {
        attempts[permission] = checks.intValue
        when (permission) {
            TriggerPermission.SMS -> runtime.launch(arrayOf(Manifest.permission.RECEIVE_SMS))
            TriggerPermission.LOCATION -> runtime.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            TriggerPermission.NOTIFICATION_ACCESS -> context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            TriggerPermission.BACKGROUND_LOCATION -> openAppInfo(context)
        }
    }
    // A grant that came back with the permission still missing: on Android 13+ that may be a restricted setting.
    val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && missing.any { permission ->
        (permission == TriggerPermission.SMS || permission == TriggerPermission.NOTIFICATION_ACCESS) &&
            attempts[permission]?.let { checks.intValue > it } == true
    }
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
        SettingsSection(stringResource(R.string.automation_permission_title), description = stringResource(R.string.automation_permission_body)) {
            missing.forEachIndexed { index, permission ->
                SettingsRow(
                    icon = permissionIcon(permission),
                    title = stringResource(permissionLabel(permission)),
                    subtitle = null,
                    shape = segmentedShape(index, missing.size),
                    onClick = { grant(permission) },
                    trailing = { Text(stringResource(R.string.automation_permission_grant), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                )
            }
        }
        if (restricted) {
            SupportingNote("info", stringResource(R.string.automation_permission_restricted))
            TextButton(onClick = { openAppInfo(context) }) { Text(stringResource(R.string.automation_permission_open_info)) }
        }
        TextButton(onClick = onNotNow) { Text(stringResource(R.string.automation_permission_not_now)) }
    }
}

private fun permissionIcon(permission: TriggerPermission) = when (permission) {
    TriggerPermission.SMS -> "sms"
    TriggerPermission.NOTIFICATION_ACCESS -> "notifications"
    TriggerPermission.LOCATION, TriggerPermission.BACKGROUND_LOCATION -> "location_on"
}

private fun permissionLabel(permission: TriggerPermission) = when (permission) {
    TriggerPermission.SMS -> R.string.automation_permission_sms
    TriggerPermission.NOTIFICATION_ACCESS -> R.string.automation_permission_notification_access
    TriggerPermission.LOCATION -> R.string.automation_permission_location
    TriggerPermission.BACKGROUND_LOCATION -> R.string.automation_permission_background_location
}

private fun openAppInfo(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

private const val MAX_DIGITS = 6
private const val MAX_PLACE_NAME = 40
