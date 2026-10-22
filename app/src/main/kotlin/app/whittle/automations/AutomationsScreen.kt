package app.whittle.automations

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.segmentedShape
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

/** The user's automations, each switchable and editable, and a way to add one. */
@Composable
fun AutomationsScreen(graph: AppGraph, onBack: () -> Unit, onRunNow: (Automation) -> Unit, onOpenChat: (ChatId) -> Unit) {
    BackHandler(onBack = onBack)
    val automations by graph.automations.all.collectAsState()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Automation?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    // Writes go to the app's scope so leaving the screen never drops a change.
    val change = { action: suspend () -> Unit -> graph.appScope.launch { action() }; Unit }
    val full = automations.size >= graph.automations.config.maxAutomations
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("arrow_back", stringResource(R.string.settings_back), onBack)
                Text(stringResource(R.string.automation_title), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            item {
                Text(
                    stringResource(R.string.automation_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = WhittleSpace.m, end = WhittleSpace.m, bottom = WhittleSpace.m),
                )
            }
            itemsIndexed(automations, key = { _, automation -> automation.id }) { index, automation ->
                val next = graph.automations.nextRunMillis(automation)
                SettingsRow(
                    icon = "schedule",
                    title = automation.name,
                    subtitle = automationSubtitle(context, automation, next),
                    shape = segmentedShape(index, automations.size),
                    modifier = Modifier.userContent(),
                    onClick = { editing = automation; editingIsNew = false },
                    trailing = {
                        Switch(
                            checked = automation.enabled,
                            onCheckedChange = { on -> change { graph.automations.setEnabled(automation.id, on) } },
                        )
                    },
                )
            }
            item {
                SettingsRow(
                    icon = "add",
                    title = stringResource(R.string.automation_new),
                    subtitle = if (full) stringResource(R.string.automation_limit, graph.automations.config.maxAutomations) else null,
                    shape = segmentedShape(0, 1),
                    enabled = !full,
                    onClick = { editing = graph.automations.draft(); editingIsNew = true },
                    modifier = Modifier.padding(top = WhittleSpace.l),
                )
            }
        }
    }
    editing?.let { automation ->
        AutomationEditorSheet(
            graph = graph,
            initial = automation,
            isNew = editingIsNew,
            onDismiss = { editing = null },
            onRunNow = { editing = null; onRunNow(it) },
            onOpenChat = { editing = null; onOpenChat(it) },
        )
    }
}

/** "Every day at 07:00", "Mon, Wed at 07:00"; [Schedule] days are named in the phone's language. */
internal fun scheduleText(context: Context, schedule: Schedule): String {
    val time = formatTime(schedule.time)
    return when (schedule.days) {
        Schedule.EVERY_DAY -> context.getString(R.string.automation_every_day, time)
        Schedule.WEEKDAYS -> context.getString(R.string.automation_weekdays, time)
        Schedule.WEEKENDS -> context.getString(R.string.automation_weekends, time)
        else -> context.getString(R.string.automation_some_days, schedule.daysOfWeek.joinToString(", ") { shortDay(it) }, time)
    }
}

/** The schedule, then when it next runs ("Next Thu 07:00") or that it's off. */
internal fun automationSubtitle(context: Context, automation: Automation, nextRunMillis: Long?): String {
    val schedule = scheduleText(context, automation.schedule)
    val tail = if (!automation.enabled) {
        context.getString(R.string.automation_off)
    } else {
        nextRunMillis?.let { millis ->
            val at = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
            context.getString(R.string.automation_next, "${shortDay(at.dayOfWeek)} ${formatTime(at.toLocalTime())}")
        }
    }
    return if (tail == null) schedule else "$schedule · $tail"
}

/** A time of day in the phone's preferred style. */
internal fun formatTime(time: LocalTime): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(time)

/** "Mon", "Tue" and so on, in the phone's language. */
internal fun shortDay(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
