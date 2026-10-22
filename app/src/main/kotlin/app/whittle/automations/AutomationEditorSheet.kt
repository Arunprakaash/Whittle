package app.whittle.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.background.rememberNotificationPermissionRequest
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
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
import java.time.LocalTime

/**
 * Adds or changes one automation: its name, what Whittle does, when, and which tools it may use without
 * asking. For a saved one it also offers Run now, opening the last run's chat and Delete.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutomationEditorSheet(
    graph: AppGraph,
    initial: Automation,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onRunNow: (Automation) -> Unit,
    onOpenChat: (ChatId) -> Unit,
) {
    val config = graph.automations.config
    var name by remember { mutableStateOf(initial.name) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var minuteOfDay by remember { mutableIntStateOf(initial.schedule.minuteOfDay) }
    var days by remember { mutableStateOf(initial.schedule.days) }
    var tools by remember { mutableStateOf(initial.tools) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val requestNotifications = rememberNotificationPermissionRequest()
    val time = Schedule(minuteOfDay, days).time
    val edited = initial.copy(name = name, prompt = prompt, schedule = Schedule(minuteOfDay, days), tools = tools)

    val problemText = mapOf(
        AutomationProblem.NAME_EMPTY to stringResource(R.string.automation_error_name_empty),
        AutomationProblem.NAME_TOO_LONG to stringResource(R.string.automation_error_name_long, config.maxNameChars),
        AutomationProblem.PROMPT_EMPTY to stringResource(R.string.automation_error_prompt_empty),
        AutomationProblem.PROMPT_TOO_LONG to stringResource(R.string.automation_error_prompt_long, config.maxPromptChars),
        AutomationProblem.TOO_MANY to stringResource(R.string.automation_error_too_many, config.maxAutomations),
        AutomationProblem.TOOL_NOT_ALLOWED to stringResource(R.string.automation_error_tool),
    )

    /** Saves [edited]; [then] runs with the saved automation once it's stored. */
    fun save(then: (Automation) -> Unit) {
        saving = true
        error = null
        scope.launch {
            when (val result = graph.automations.save(edited)) {
                is SaveResult.Saved -> then(result.automation)
                is SaveResult.Refused -> error = problemText.getValue(result.problem)
                is SaveResult.Failed -> error = result.error.message
            }
            saving = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!saving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = WhittleSpace.xxl),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
            ) {
                Text(
                    stringResource(if (isNew) R.string.automation_new else R.string.automation_edit),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(config.maxNameChars); error = null },
                    label = { Text(stringResource(R.string.automation_name)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().userContent(),
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it.take(config.maxPromptChars); error = null },
                    label = { Text(stringResource(R.string.automation_prompt)) },
                    supportingText = { Text(stringResource(R.string.automation_prompt_hint)) },
                    minLines = 3,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().userContent(),
                )
                SettingsRow(
                    icon = "schedule",
                    title = stringResource(R.string.automation_time),
                    subtitle = formatTime(time),
                    shape = segmentedShape(0, 1),
                    enabled = !saving,
                    onClick = { pickingTime = true },
                )
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    Text(
                        stringResource(R.string.automation_days),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = WhittleSpace.m),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                        DayOfWeek.entries.forEach { day ->
                            val selected = day.value in days
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    // At least one day stays on.
                                    if (!selected || days.size > 1) {
                                        haptics.perform(WhittleHaptic.Select)
                                        days = if (selected) days - day.value else days + day.value
                                    }
                                },
                                enabled = !saving,
                                label = { Text(shortDay(day)) },
                            )
                        }
                    }
                }
                SettingsSection(
                    title = stringResource(R.string.automation_tools),
                    description = stringResource(R.string.automation_tools_hint),
                ) {
                    config.tools.forEachIndexed { index, choice ->
                        SettingsToggleRow(
                            icon = choice.icon,
                            title = choice.label,
                            subtitle = null,
                            checked = tools.containsAll(choice.toolIds),
                            onCheckedChange = { on -> tools = if (on) tools + choice.toolIds else tools - choice.toolIds.toSet() },
                            shape = segmentedShape(index, config.tools.size),
                            enabled = !saving,
                        )
                    }
                }
            }
            Column(
                Modifier.padding(horizontal = WhittleSpace.xxl, vertical = WhittleSpace.l),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
            ) {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
                Button(
                    onClick = { save { _ -> requestNotifications(); onDismiss() } },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.automation_save)) }
                if (!isNew) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { if (edited == initial) onRunNow(initial) else save { saved -> onRunNow(saved) } },
                            enabled = !saving,
                        ) {
                            WhittleIcon("play_arrow", size = WhittleSize.Icon.m)
                            Text(stringResource(R.string.automation_run_now), Modifier.padding(start = WhittleSpace.s))
                        }
                        initial.lastChatId?.let { chatId ->
                            TextButton(onClick = { onOpenChat(ChatId(chatId)) }, enabled = !saving) {
                                Text(stringResource(R.string.automation_open_last))
                            }
                        }
                        TextButton(onClick = { confirmingDelete = true }, enabled = !saving) {
                            Text(stringResource(R.string.automation_delete), color = colors.error)
                        }
                    }
                }
            }
        }
    }
    if (pickingTime) {
        val context = LocalContext.current
        val state = rememberTimePickerState(time.hour, time.minute, is24Hour = android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    minuteOfDay = LocalTime.of(state.hour, state.minute).let { it.hour * Schedule.MINUTES_PER_HOUR + it.minute }
                    pickingTime = false
                }) { Text(stringResource(R.string.automation_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.connector_cancel)) } },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.automation_delete_title, initial.name)) },
            text = { Text(stringResource(R.string.automation_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    graph.appScope.launch { graph.automations.delete(initial.id) }
                    onDismiss()
                }) { Text(stringResource(R.string.automation_delete), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.connector_cancel)) } },
        )
    }
}
