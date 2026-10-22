package app.whittle.automations

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import app.whittle.design.components.BottomActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.ScreenHeading
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WelcomeMascot
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.observability.posthog.userContent
import app.whittle.storage.chats.ChatId
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * The user's automations, read like alarms: the time first, the days at a glance, a switch. A new one starts from
 * the floating button, or from an idea while there are none. Adding or changing one opens [AutomationEditor].
 */
@Composable
fun AutomationsScreen(
    graph: AppGraph,
    projects: List<String>,
    onBack: () -> Unit,
    onRunNow: (Automation) -> Unit,
    onOpenChat: (ChatId) -> Unit,
) {
    var editing by remember { mutableStateOf<Automation?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    val current = editing
    if (current != null) {
        AutomationEditor(
            graph = graph,
            initial = current,
            projects = projects,
            isNew = editingIsNew,
            onClose = { editing = null },
            onRunNow = { editing = null; onRunNow(it) },
            onOpenChat = { editing = null; onOpenChat(it) },
        )
    } else {
        AutomationsList(
            graph = graph,
            onBack = onBack,
            onOpen = { automation, isNew -> editingIsNew = isNew; editing = automation },
        )
    }
}

@Composable
private fun AutomationsList(graph: AppGraph, onBack: () -> Unit, onOpen: (Automation, Boolean) -> Unit) {
    BackHandler(onBack = onBack)
    val automations by graph.automations.all.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val config = graph.automations.config
    // Writes go to the app's scope so leaving the screen never drops a change.
    val change = { action: suspend () -> Unit -> graph.appScope.launch { action() }; Unit }
    val full = automations.size >= config.maxAutomations
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s)) {
                TopBarButton("arrow_back", stringResource(R.string.settings_back), onBack)
            }
        },
        bottomBar = {
            if (!full) BottomActionButton("add", stringResource(R.string.automation_new), { onOpen(graph.automations.draft(), true) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = WhittleSpace.l, end = WhittleSpace.l, top = WhittleSpace.s, bottom = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            item {
                ScreenHeading(
                    stringResource(R.string.automation_title),
                    stringResource(R.string.automation_intro),
                    Modifier.padding(start = WhittleSpace.s, end = WhittleSpace.s, bottom = WhittleSpace.xl),
                )
            }
            if (automations.isEmpty()) {
                item { EmptyAutomations(config.ideas, resources) { idea -> onOpen(graph.automations.draft(idea), true) } }
            }
            itemsIndexed(automations, key = { _, automation -> automation.id }) { index, automation ->
                AutomationRow(
                    automation = automation,
                    status = automationStatus(resources, automation, graph.automations.nextRunMillis(automation), graph.automations.zone, graph.clock.nowMillis()),
                    shape = segmentedShape(index, automations.size),
                    onClick = { onOpen(automation, false) },
                    onEnabledChange = { on -> change { graph.automations.setEnabled(automation.id, on) } },
                )
            }
            if (automations.isNotEmpty()) {
                item {
                    SupportingNote(
                        "forum",
                        if (full) stringResource(R.string.automation_limit, config.maxAutomations) else stringResource(R.string.automation_ask_tip),
                        Modifier.padding(top = WhittleSpace.l),
                    )
                }
            }
        }
    }
}

/** None yet: what an automation is, and a few to start from. */
@Composable
private fun EmptyAutomations(ideas: List<AutomationIdea>, resources: Resources, onIdea: (AutomationIdea) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = WhittleSpace.xxxl), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl)) {
        Column(Modifier.padding(horizontal = WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl)) {
            WelcomeMascot()
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                Text(stringResource(R.string.automation_empty_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.automation_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ideas.isNotEmpty()) {
            SettingsSection(title = stringResource(R.string.automation_ideas)) {
                ideas.forEachIndexed { index, idea ->
                    SettingsRow(
                        icon = idea.icon,
                        title = idea.name,
                        subtitle = scheduleText(resources, idea.schedule),
                        shape = segmentedShape(index, ideas.size),
                        onClick = { onIdea(idea) },
                        trailing = { WhittleIcon("chevron_right", size = WhittleSize.Icon.l, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
    }
}

/** One automation: its time, name, days and what happens next, with its switch. */
@Composable
private fun AutomationRow(automation: Automation, status: AutomationStatus, shape: Shape, onClick: () -> Unit, onEnabledChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val content = if (automation.enabled) colors.onSurface else colors.onSurfaceVariant
    val statusColor = if (status.missed) colors.error else colors.onSurfaceVariant
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onClick() }
            .padding(start = WhittleSpace.xl, end = WhittleSpace.l, top = WhittleSpace.m, bottom = WhittleSpace.l),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(automation.schedule.time), style = MaterialTheme.typography.displaySmall, color = content, modifier = Modifier.weight(1f))
            Switch(checked = automation.enabled, onCheckedChange = { haptics.perform(WhittleHaptic.Select); onEnabledChange(it) })
        }
        Text(automation.name, style = MaterialTheme.typography.titleMedium, color = content, modifier = Modifier.userContent())
        DayMarks(automation.schedule.days, automation.enabled, daysText(LocalResources.current, automation.schedule.days))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
            WhittleIcon(status.icon, size = WhittleSize.Icon.s, tint = statusColor)
            Text(status.text, style = MaterialTheme.typography.bodySmall, color = statusColor)
            automation.project?.let { project ->
                WhittleIcon("folder", size = WhittleSize.Icon.s, tint = colors.onSurfaceVariant, modifier = Modifier.padding(start = WhittleSpace.s))
                Text(project, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.userContent())
            }
        }
    }
}

/** The week as letters, the automation's days filled; read out as the days themselves. */
@Composable
private fun DayMarks(days: Set<Int>, enabled: Boolean, description: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.clearAndSetSemantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
        DayOfWeek.entries.forEach { day ->
            val on = day.value in days
            val (background, content) = when {
                !on -> colors.surfaceContainerLow to colors.onSurfaceVariant
                enabled -> colors.secondaryContainer to colors.onSecondaryContainer
                else -> colors.surfaceContainerHigh to colors.onSurface
            }
            Box(Modifier.size(WhittleSize.Tile.s).background(background, CircleShape), contentAlignment = Alignment.Center) {
                Text(narrowDay(day), style = if (on) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall, color = content)
            }
        }
    }
}

/** What happens next for an automation, as its row and its editor show it. */
internal data class AutomationStatus(val icon: String, val text: String, val missed: Boolean = false)

internal fun automationStatus(resources: Resources, automation: Automation, nextRunMillis: Long?, zone: ZoneId, nowMillis: Long): AutomationStatus = when {
    !automation.enabled -> AutomationStatus("pause_circle", resources.getString(R.string.automation_off))
    automation.missedAtMillis != null -> AutomationStatus(
        "error",
        resources.getString(R.string.automation_missed, dayAndTime(resources, automation.missedAtMillis, zone, nowMillis)),
        missed = true,
    )
    nextRunMillis != null -> AutomationStatus("schedule", resources.getString(R.string.automation_next, dayAndTime(resources, nextRunMillis, zone, nowMillis)))
    else -> AutomationStatus("pause_circle", resources.getString(R.string.automation_off))
}

/** "today, 07:30", "tomorrow, 07:30", "Thu, 07:30", in [zone], next to [nowMillis]. */
internal fun dayAndTime(resources: Resources, millis: Long, zone: ZoneId, nowMillis: Long): String {
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val day = when (at.toLocalDate()) {
        today -> resources.getString(R.string.automation_today)
        today.plusDays(1) -> resources.getString(R.string.automation_tomorrow)
        today.minusDays(1) -> resources.getString(R.string.automation_yesterday)
        else -> shortDay(at.dayOfWeek)
    }
    return resources.getString(R.string.automation_day_at, day, formatTime(at.toLocalTime()))
}

/** "Every day", "Weekdays", "Weekends" or "Mon, Wed"; days are named in the phone's language. */
internal fun daysText(resources: Resources, days: Set<Int>): String = when (days) {
    Schedule.EVERY_DAY -> resources.getString(R.string.automation_every_day)
    Schedule.WEEKDAYS -> resources.getString(R.string.automation_weekdays)
    Schedule.WEEKENDS -> resources.getString(R.string.automation_weekends)
    else -> days.sorted().joinToString(", ") { shortDay(DayOfWeek.of(it)) }
}

/** "Weekdays at 07:30". */
internal fun scheduleText(resources: Resources, schedule: Schedule): String =
    resources.getString(R.string.automation_schedule, daysText(resources, schedule.days), formatTime(schedule.time))

/** A time of day in the phone's preferred style. */
internal fun formatTime(time: LocalTime): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(time)

/** "Mon", "Tue" and so on, in the phone's language. */
internal fun shortDay(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** "Monday" and so on, in the phone's language. */
internal fun fullDay(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.getDefault())

/** "M", "T" and so on, in the phone's language. */
internal fun narrowDay(day: DayOfWeek): String = day.getDisplayName(TextStyle.NARROW, Locale.getDefault())
