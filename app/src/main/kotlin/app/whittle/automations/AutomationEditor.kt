package app.whittle.automations

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.background.rememberNotificationPermissionRequest
import app.whittle.design.components.MenuAction
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.phone.rememberCapabilityRequest
import app.whittle.tools.api.CapabilityId
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleFilledField
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.connectedShape
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.observability.posthog.userContent
import app.whittle.storage.chats.ChatId
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Adds or changes one automation, full screen: close and Save at the top, then what it does, when, where and what it
 * may use without asking. A saved one starts with its last run, Run now and Open chat; Turn off and Delete are in
 * the top bar's menu.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AutomationEditor(
    graph: AppGraph,
    initial: Automation,
    projects: List<String>,
    isNew: Boolean,
    onClose: () -> Unit,
    onRunNow: (Automation) -> Unit,
    onOpenChat: (ChatId) -> Unit,
) {
    BackHandler(onBack = onClose)
    val config = graph.automations.config
    // The answer goes to whichever row asked last.
    var answer by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    val capabilityRequest = rememberCapabilityRequest(graph.healthConnect) { granted -> answer(granted) }
    val request: (List<CapabilityId>, (Boolean) -> Unit) -> Unit = { capabilities, onDone -> answer = onDone; capabilityRequest(capabilities) }
    val stored by graph.automations.all.collectAsStateWithLifecycle()
    // Runs and the switch change the stored automation while the editor is open; what the user typed stays.
    val live = stored.firstOrNull { it.id == initial.id } ?: initial
    var name by remember { mutableStateOf(initial.name) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var minuteOfDay by remember { mutableIntStateOf(initial.schedule.minuteOfDay) }
    var days by remember { mutableStateOf(initial.schedule.days) }
    var tools by remember { mutableStateOf(initial.tools) }
    var project by remember { mutableStateOf(initial.project) }
    var pickingProject by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var showingMenu by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val resources = LocalResources.current
    val requestNotifications = rememberNotificationPermissionRequest()
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val schedule = Schedule(minuteOfDay, days)
    val edited = initial.copy(name = name, prompt = prompt, schedule = schedule, tools = tools, project = project)
    val changed = edited != initial

    val problemText = mapOf(
        AutomationProblem.NAME_EMPTY to stringResource(R.string.automation_error_name_empty),
        AutomationProblem.NAME_TOO_LONG to pluralStringResource(R.plurals.automation_error_name_long, config.maxNameChars, config.maxNameChars),
        AutomationProblem.PROMPT_EMPTY to stringResource(R.string.automation_error_prompt_empty),
        AutomationProblem.PROMPT_TOO_LONG to pluralStringResource(R.plurals.automation_error_prompt_long, config.maxPromptChars, config.maxPromptChars),
        AutomationProblem.TOO_MANY to pluralStringResource(R.plurals.automation_error_too_many, config.maxAutomations, config.maxAutomations),
        AutomationProblem.PROJECT_INVALID to stringResource(R.string.automation_error_project),
        AutomationProblem.TOOL_NOT_ALLOWED to stringResource(R.string.automation_error_tool),
    )

    /** Stores [edited]; [then] runs with the saved automation once it's stored. */
    fun store(then: (Automation) -> Unit) {
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

    /**
     * Saves [edited], first asking for what its switched-on rows need (reading health data while closed, say), as one
     * that started from an idea was never switched on by hand. A row whose permission is refused goes back off, unsaved.
     */
    fun save(then: (Automation) -> Unit) {
        val needing = config.tools.filter { it.capabilities.isNotEmpty() && tools.containsAll(it.toolIds) }
        if (needing.isEmpty()) return store(then)
        request(needing.flatMap { it.capabilities }.distinct().map(::CapabilityId)) { granted ->
            if (granted) store(then) else tools = tools - needing.flatMap { it.toolIds }.toSet()
        }
    }

    Scaffold(
        containerColor = colors.surface,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    // Once the form scrolls under it, the bar takes a container colour so the two read apart.
                    .background(if (scrolled) colors.surfaceContainerLow else colors.surface)
                    .statusBarsPadding()
                    .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
            ) {
                TopBarButton("close", stringResource(R.string.automation_close), onClose)
                Text(
                    if (isNew) stringResource(R.string.automation_new) else initial.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(if (isNew) Modifier else Modifier.userContent()),
                )
                Button(
                    onClick = { haptics.perform(WhittleHaptic.Approve); save { _ -> requestNotifications(); onClose() } },
                    enabled = !saving && (isNew || changed),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.height(WhittleSize.Button.small),
                ) { Text(stringResource(R.string.automation_save)) }
                if (!isNew) {
                    Box {
                        TopBarButton("more_vert", stringResource(R.string.automation_more), { showingMenu = true })
                        WhittleMenu(
                            expanded = showingMenu,
                            onDismiss = { showingMenu = false },
                            groups = listOf(
                                listOf(
                                    MenuAction(
                                        label = stringResource(if (live.enabled) R.string.automation_turn_off else R.string.automation_turn_on),
                                        icon = if (live.enabled) "toggle_off" else "toggle_on",
                                        onClick = { graph.appScope.launch { graph.automations.setEnabled(initial.id, !live.enabled) } },
                                    ),
                                ),
                                listOf(MenuAction(stringResource(R.string.automation_delete), "delete", { confirmingDelete = true }, destructive = true)),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scroll)
                .padding(start = WhittleSpace.l, end = WhittleSpace.l, top = WhittleSpace.s, bottom = WhittleSpace.xxxl),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl),
        ) {
            if (!isNew) {
                LastRun(
                    automation = live,
                    status = automationStatus(resources, live, graph.automations.nextRunMillis(live), graph.automations.zone, graph.clock.nowMillis()),
                    lastRun = live.lastRunAtMillis?.let { dayAndTime(resources, it, graph.automations.zone, graph.clock.nowMillis()) },
                    enabled = !saving,
                    onRunNow = { if (changed) save { saved -> onRunNow(saved) } else onRunNow(live) },
                    onOpenChat = { live.lastChatId?.let { onOpenChat(ChatId(it)) } },
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(horizontal = WhittleSpace.s)) }
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                WhittleFilledField(
                    value = name,
                    onValueChange = { name = it.take(config.maxNameChars); error = null },
                    label = stringResource(R.string.automation_name),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.userContent(),
                )
                WhittleFilledField(
                    value = prompt,
                    onValueChange = { prompt = it.take(config.maxPromptChars); error = null },
                    label = stringResource(R.string.automation_prompt),
                    minLines = PROMPT_LINES,
                    hint = stringResource(R.string.automation_prompt_hint),
                    enabled = !saving,
                    modifier = Modifier.userContent(),
                )
            }
            Section(stringResource(R.string.automation_when)) {
                When(
                    schedule = schedule,
                    summary = stringResource(
                        R.string.automation_when_summary,
                        daysText(resources, days),
                        graph.automations.nextRunMillis(edited.copy(enabled = true))
                            ?.let { dayAndTime(resources, it, graph.automations.zone, graph.clock.nowMillis()) }
                            .orEmpty(),
                    ),
                    enabled = !saving,
                    onPickTime = { pickingTime = true },
                    onDaysChange = { days = it },
                )
            }
            Section(stringResource(R.string.automation_where)) {
                Box {
                    SettingsRow(
                        icon = if (project != null) "folder" else "workspaces",
                        title = project ?: stringResource(R.string.automation_project_whole),
                        subtitle = stringResource(if (project != null) R.string.automation_project_kept else R.string.automation_project_pick),
                        shape = segmentedShape(0, 1),
                        enabled = !saving,
                        onClick = { pickingProject = true },
                        modifier = Modifier.userContent(),
                        trailing = { WhittleIcon("expand_more", size = WhittleSize.Icon.l, tint = colors.onSurfaceVariant) },
                    )
                    WhittleMenu(
                        expanded = pickingProject,
                        onDismiss = { pickingProject = false },
                        groups = listOf(
                            listOf(MenuAction(stringResource(R.string.automation_project_whole), "workspaces", { project = null; error = null })),
                            // A project whose folder is gone stays pickable so it isn't lost silently.
                            (projects + listOfNotNull(project).filter { it !in projects }).map { folder ->
                                MenuAction(folder, "folder", { project = folder; error = null })
                            },
                        ).filter { it.isNotEmpty() },
                    )
                }
            }
            SettingsSection(
                title = stringResource(R.string.automation_tools),
                description = stringResource(R.string.automation_tools_hint),
            ) {
                config.tools.forEachIndexed { index, choice ->
                    SettingsToggleRow(
                        icon = choice.icon,
                        title = choice.labelFor(project),
                        subtitle = null,
                        checked = tools.containsAll(choice.toolIds),
                        onCheckedChange = { on ->
                            tools = if (on) tools + choice.toolIds else tools - choice.toolIds.toSet()
                            // A row that needs a permission (reading health data while closed) asks for it now, and goes
                            // back off if it isn't granted.
                            if (on && choice.capabilities.isNotEmpty()) {
                                request(choice.capabilities.map(::CapabilityId)) { granted -> if (!granted) tools = tools - choice.toolIds.toSet() }
                            }
                        },
                        shape = segmentedShape(index, config.tools.size),
                        enabled = !saving,
                    )
                }
            }
            SupportingNote(
                "notifications",
                name.trim().takeIf { it.isNotEmpty() }?.let { stringResource(R.string.automation_runs_note, it) }
                    ?: stringResource(R.string.automation_runs_note_unnamed),
            )
        }
    }
    if (pickingTime) {
        val context = LocalContext.current
        val time = schedule.time
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
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(R.string.automation_cancel)) } },
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
                    onClose()
                }) { Text(stringResource(R.string.automation_delete), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.automation_cancel)) } },
        )
    }
}

/** A titled part of the form. */
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = WhittleSpace.m))
        content()
    }
}

/** The last run, what happens next, and Run now with Open chat. */
@Composable
private fun LastRun(automation: Automation, status: AutomationStatus, lastRun: String?, enabled: Boolean, onRunNow: () -> Unit, onOpenChat: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val canOpen = automation.lastChatId != null
    val buttons = if (canOpen) 2 else 1
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(WhittleRadius.xl)).background(colors.surfaceContainerLow).padding(WhittleSpace.l),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Box(
                Modifier.size(WhittleSize.Tile.m).background(if (status.missed) colors.errorContainer else colors.secondaryContainer, RoundedCornerShape(WhittleRadius.m)),
                contentAlignment = Alignment.Center,
            ) {
                WhittleIcon(
                    if (status.missed) "error" else "task_alt",
                    size = WhittleSize.Icon.l,
                    tint = if (status.missed) colors.onErrorContainer else colors.onSecondaryContainer,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
                Text(
                    lastRun?.let { stringResource(R.string.automation_last_run, it) } ?: stringResource(R.string.automation_never_run),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(status.text, style = MaterialTheme.typography.bodySmall, color = if (status.missed) colors.error else colors.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
            Button(
                onClick = { haptics.perform(WhittleHaptic.Tap); onRunNow() },
                enabled = enabled,
                shape = connectedShape(0, buttons),
                modifier = Modifier.weight(1f).height(WhittleSize.Button.default),
            ) {
                WhittleIcon("play_arrow", size = WhittleSize.Icon.l)
                Spacer(Modifier.size(WhittleSpace.s))
                Text(stringResource(R.string.automation_run_now))
            }
            if (canOpen) {
                Button(
                    onClick = { haptics.perform(WhittleHaptic.Tap); onOpenChat() },
                    enabled = enabled,
                    shape = connectedShape(1, buttons),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceContainerHigh, contentColor = colors.onSurface),
                    modifier = Modifier.weight(1f).height(WhittleSize.Button.default),
                ) {
                    WhittleIcon("chat", size = WhittleSize.Icon.l)
                    Spacer(Modifier.size(WhittleSpace.s))
                    Text(stringResource(R.string.automation_open_chat))
                }
            }
        }
    }
}

/** The time, large, to tap and change; the days as seven buttons; and when it next runs. */
@Composable
private fun When(schedule: Schedule, summary: String, enabled: Boolean, onPickTime: () -> Unit, onDaysChange: (Set<Int>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val time = formatTime(schedule.time)
    val changeTime = stringResource(R.string.automation_change_time, time)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(WhittleRadius.xl)).background(colors.surfaceContainerLow).padding(WhittleSpace.l),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(WhittleRadius.l))
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = changeTime) { haptics.perform(WhittleHaptic.Tap); onPickTime() }
                .semantics { contentDescription = changeTime },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        ) {
            Text(time, style = MaterialTheme.typography.displayMedium)
            WhittleIcon("edit", size = WhittleSize.Icon.xl, tint = colors.primary)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            DayOfWeek.entries.forEach { day ->
                val on = day.value in schedule.days
                // Expressive toggles change shape as well as colour: round when off, a rounded square when on.
                val corner by animateDpAsState(if (on) WhittleRadius.m else WhittleSize.Tile.m / 2, label = "day corner")
                val label = fullDay(day)
                Box(
                    Modifier
                        .size(WhittleSize.Tile.m)
                        .clip(RoundedCornerShape(corner))
                        .background(if (on) colors.primary else colors.surfaceContainerHigh)
                        .toggleable(value = on, enabled = enabled, role = Role.Checkbox) { checked ->
                            // At least one day stays on.
                            if (checked || schedule.days.size > 1) {
                                haptics.perform(WhittleHaptic.Select)
                                onDaysChange(if (checked) schedule.days + day.value else schedule.days - day.value)
                            }
                        }
                        .semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(narrowDay(day), style = MaterialTheme.typography.labelLarge, color = if (on) colors.onPrimary else colors.onSurface)
                }
            }
        }
        Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** How tall the instruction field starts, in lines. */
private const val PROMPT_LINES = 4
