package app.whittle.automations.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.design.components.ReadingLazyColumn
import app.whittle.design.components.ReadingScrollColumn
import app.whittle.design.components.BottomActionButton
import app.whittle.design.components.MenuAction
import app.whittle.design.components.ScreenHeading
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WelcomeMascot
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
import app.whittle.design.window.BackHandler
import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.WindowTopBar
import app.whittle.design.window.userContent
import app.whittle.files.ui.nowMillis
import app.whittle.settings.ui.resources.Res
import app.whittle.settings.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The user's automations, read like alarms: the time first, the days at a glance, a switch. A new one starts from
 * the button at the bottom, or from an idea while there are none. Adding or changing one opens the editor.
 */
@Composable
fun AutomationsScreen(host: AutomationsHost, platform: AutomationsPlatform, navigation: AutomationsNavigation) {
    var editing by remember { mutableStateOf<AutomationEntry?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    val current = editing
    if (current != null) {
        AutomationEditor(host, platform, navigation, initial = current, isNew = editingIsNew, onClose = { editing = null })
    } else {
        AutomationsList(host, onBack = navigation.back, onOpen = { automation, isNew -> editingIsNew = isNew; editing = automation })
    }
}

/** A top bar inside a computer's window: clear of its own buttons, and moving the window when dragged. */
@Composable
private fun TopBarRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    WindowTopBar {
        Row(
            modifier.fillMaxWidth().statusBarsPadding().windowInsetsPadding(LocalWindowChrome.current?.insets ?: WindowInsets(0))
                .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        ) { content() }
    }
}

@Composable
private fun AutomationsList(host: AutomationsHost, onBack: () -> Unit, onOpen: (AutomationEntry, Boolean) -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    val state by host.state.collectAsState()
    val automations = state.automations
    val full = state.maxAutomations > 0 && automations.size >= state.maxAutomations
    val now = nowMillis()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBarRow { TopBarButton("arrow_back", stringResource(Res.string.settings_back), onBack) } },
        bottomBar = {
            val blank = state.blank
            if (!full && blank != null) BottomActionButton("add", stringResource(Res.string.automation_new), { onOpen(blank, true) })
        },
    ) { padding ->
        // On a wide window the page reads in a centred column; the whole width still scrolls.
        ReadingLazyColumn(
            minInset = WhittleSpace.l,
            modifier = Modifier.padding(padding).fillMaxSize(),
            top = WhittleSpace.s,
            bottom = WhittleSpace.l,
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            item {
                ScreenHeading(
                    stringResource(Res.string.automation_title),
                    stringResource(Res.string.automation_intro),
                    Modifier.padding(start = WhittleSpace.s, end = WhittleSpace.s, bottom = WhittleSpace.xl),
                )
            }
            if (automations.isEmpty()) {
                item { EmptyAutomations(state.ideas) { idea -> onOpen(idea.draft, true) } }
            }
            itemsIndexed(automations, key = { _, automation -> automation.id }) { index, automation ->
                AutomationRow(
                    automation = automation,
                    status = automationStatus(automation, state.zone, now),
                    shape = segmentedShape(index, automations.size),
                    onClick = { onOpen(automation, false) },
                    onEnabledChange = { on -> host.act(AutomationAction.SetEnabled(automation.id, on)) },
                )
            }
            if (automations.isNotEmpty()) {
                item {
                    SupportingNote(
                        "forum",
                        if (full) stringResource(Res.string.automation_limit, state.maxAutomations) else stringResource(Res.string.automation_ask_tip),
                        Modifier.padding(top = WhittleSpace.l),
                    )
                }
                // The recipes stay one tap away after the first automation, without the ones already made.
                val more = unusedIdeas(state.ideas, automations)
                if (!full && more.isNotEmpty()) {
                    item {
                        IdeasSection(more, stringResource(Res.string.automation_more_ideas), Modifier.padding(top = WhittleSpace.xxl)) { idea -> onOpen(idea.draft, true) }
                    }
                }
            }
        }
    }
}

/** None yet: what an automation is, and a few to start from. */
@Composable
private fun EmptyAutomations(ideas: List<AutomationIdea>, onIdea: (AutomationIdea) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = WhittleSpace.xxxl), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl)) {
        Column(Modifier.padding(horizontal = WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl)) {
            WelcomeMascot()
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                Text(stringResource(Res.string.automation_empty_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.automation_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ideas.isNotEmpty()) IdeasSection(ideas, stringResource(Res.string.automation_ideas), onIdea = onIdea)
    }
}

/** Ready-made automations, each with its schedule; tapping one opens the editor filled in. */
@Composable
private fun IdeasSection(ideas: List<AutomationIdea>, title: String, modifier: Modifier = Modifier, onIdea: (AutomationIdea) -> Unit) {
    SettingsSection(title = title, modifier = modifier) {
        ideas.forEachIndexed { index, idea ->
            SettingsRow(
                icon = idea.icon,
                title = idea.name,
                subtitle = scheduleText(idea.draft.minuteOfDay, idea.draft.days),
                shape = segmentedShape(index, ideas.size),
                onClick = { onIdea(idea) },
                trailing = { WhittleIcon("chevron_right", size = WhittleSize.Icon.l, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
            )
        }
    }
}

/** One automation: its time, name, days and what happens next, with its switch. */
@Composable
private fun AutomationRow(automation: AutomationEntry, status: AutomationStatus, shape: Shape, onClick: () -> Unit, onEnabledChange: (Boolean) -> Unit) {
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
            Text(formatTime(automation.minuteOfDay), style = MaterialTheme.typography.displaySmall, color = content, modifier = Modifier.weight(1f))
            Switch(checked = automation.enabled, onCheckedChange = { haptics.perform(WhittleHaptic.Select); onEnabledChange(it) })
        }
        Text(automation.name, style = MaterialTheme.typography.titleMedium, color = content, modifier = Modifier.userContent())
        DayMarks(automation.days, automation.enabled, daysText(automation.days))
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
        WEEK.forEach { day ->
            val on = day in days
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
private data class AutomationStatus(val icon: String, val text: String, val missed: Boolean = false)

@Composable
private fun automationStatus(automation: AutomationEntry, zone: String, nowMillis: Long): AutomationStatus {
    val missedAt = automation.missedAtMillis
    val next = automation.nextRunMillis
    return when {
        !automation.enabled -> AutomationStatus("pause_circle", stringResource(Res.string.automation_off))
        missedAt != null -> AutomationStatus("error", stringResource(Res.string.automation_missed, dayAndTime(missedAt, zone, nowMillis)), missed = true)
        next != null -> AutomationStatus("schedule", stringResource(Res.string.automation_next, dayAndTime(next, zone, nowMillis)))
        else -> AutomationStatus("pause_circle", stringResource(Res.string.automation_off))
    }
}

/**
 * Adds or changes one automation, full screen: close and Save at the top, then what it does, when, where and what it
 * may use without asking. A saved one starts with its last run, Run now and Open chat; Turn off and Delete are in
 * the top bar's menu.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AutomationEditor(
    host: AutomationsHost,
    platform: AutomationsPlatform,
    navigation: AutomationsNavigation,
    initial: AutomationEntry,
    isNew: Boolean,
    onClose: () -> Unit,
) {
    BackHandler(enabled = true, onBack = onClose)
    val state by host.state.collectAsState()
    // Runs and the switch change the stored automation while the editor is open; what the user typed stays.
    val live = state.automations.firstOrNull { it.id == initial.id } ?: initial
    var name by remember { mutableStateOf(initial.name) }
    var prompt by remember { mutableStateOf(initial.prompt) }
    var minuteOfDay by remember { mutableIntStateOf(initial.minuteOfDay) }
    var days by remember { mutableStateOf(initial.days) }
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
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val now = nowMillis()
    val edited = initial.copy(name = name, prompt = prompt, minuteOfDay = minuteOfDay, days = days, tools = tools, project = project)
    val changed = edited != initial
    val problems = issueTexts(state)
    val saveFailed = stringResource(Res.string.memory_save_failed)
    val busy = stringResource(Res.string.automation_busy)
    val noModel = stringResource(Res.string.automation_no_model)

    /** Stores [edited]; [then] runs with the saved automation once it's stored. */
    fun store(then: (AutomationEntry) -> Unit) {
        saving = true
        error = null
        scope.launch {
            when (val result = host.save(edited)) {
                is AutomationSave.Saved -> then(result.automation)
                is AutomationSave.Refused -> error = problems.getValue(result.issue)
                is AutomationSave.Failed -> error = result.message ?: saveFailed
            }
            saving = false
        }
    }

    /**
     * Saves [edited], first asking for what its switched-on rows need (reading health data while closed, say), as one
     * that started from an idea was never switched on by hand. A row whose permission is refused goes back off, unsaved.
     */
    fun save(then: (AutomationEntry) -> Unit) {
        val needing = state.tools.filter { it.capabilities.isNotEmpty() && tools.containsAll(it.toolIds) }
        val ask = platform.requestCapabilities
        if (needing.isEmpty() || ask == null) return store(then)
        ask(needing.flatMap { it.capabilities }.distinct()) { granted ->
            if (granted) store(then) else tools = tools - needing.flatMap { it.toolIds }.toSet()
        }
    }

    fun runNow(automation: AutomationEntry) {
        scope.launch {
            when (host.runNow(automation.id)) {
                RunNow.Started -> navigation.runStarted()
                RunNow.Busy -> platform.toast(busy)
                RunNow.NoModel -> platform.toast(noModel)
                RunNow.NotFound -> Unit
            }
        }
    }

    Scaffold(
        containerColor = colors.surface,
        topBar = {
            // Once the form scrolls under it, the bar takes a container colour so the two read apart.
            TopBarRow(Modifier.background(if (scrolled) colors.surfaceContainerLow else colors.surface)) {
                TopBarButton("close", stringResource(Res.string.automation_close), onClose)
                Text(
                    if (isNew) stringResource(Res.string.automation_new) else initial.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(if (isNew) Modifier else Modifier.userContent()),
                )
                Button(
                    onClick = { haptics.perform(WhittleHaptic.Approve); save { _ -> platform.requestNotifications(); onClose() } },
                    enabled = !saving && (isNew || changed),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.height(WhittleSize.Button.small),
                ) { Text(stringResource(Res.string.automation_save)) }
                if (!isNew) {
                    Box {
                        TopBarButton("more_vert", stringResource(Res.string.automation_more), { showingMenu = true })
                        WhittleMenu(
                            expanded = showingMenu,
                            onDismiss = { showingMenu = false },
                            groups = listOf(
                                listOf(
                                    MenuAction(
                                        label = stringResource(if (live.enabled) Res.string.automation_turn_off else Res.string.automation_turn_on),
                                        icon = if (live.enabled) "toggle_off" else "toggle_on",
                                        onClick = { host.act(AutomationAction.SetEnabled(initial.id, !live.enabled)) },
                                    ),
                                ),
                                listOf(MenuAction(stringResource(Res.string.automation_delete), "delete", { confirmingDelete = true }, destructive = true)),
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        // On a wide window the page reads in a centred column; the whole width still scrolls.
        ReadingScrollColumn(
            minInset = WhittleSpace.l,
            modifier = Modifier.padding(padding).fillMaxSize().imePadding(),
            scroll = scroll,
            top = WhittleSpace.s,
            bottom = WhittleSpace.xxxl,
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl),
        ) {
            if (!isNew) {
                LastRun(
                    automation = live,
                    status = automationStatus(live, state.zone, now),
                    lastRun = live.lastRunAtMillis?.let { dayAndTime(it, state.zone, now) },
                    enabled = !saving,
                    onRunNow = { if (changed) save { saved -> runNow(saved) } else runNow(live) },
                    onOpenChat = { live.lastChatId?.let(navigation.openChat) },
                )
            }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(horizontal = WhittleSpace.s)) }
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                WhittleFilledField(
                    value = name,
                    onValueChange = { name = it.take(state.maxNameChars.takeIf { max -> max > 0 } ?: it.length); error = null },
                    label = stringResource(Res.string.automation_name),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.userContent(),
                )
                WhittleFilledField(
                    value = prompt,
                    onValueChange = { prompt = it.take(state.maxPromptChars.takeIf { max -> max > 0 } ?: it.length); error = null },
                    label = stringResource(Res.string.automation_prompt),
                    minLines = PROMPT_LINES,
                    hint = stringResource(Res.string.automation_prompt_hint),
                    enabled = !saving,
                    modifier = Modifier.userContent(),
                )
            }
            Section(stringResource(Res.string.automation_when)) {
                When(
                    minuteOfDay = minuteOfDay,
                    days = days,
                    summary = stringResource(
                        Res.string.automation_when_summary,
                        daysText(days),
                        nextRun(minuteOfDay, days, state.zone, now)?.let { dayAndTime(it, state.zone, now) }.orEmpty(),
                    ),
                    enabled = !saving,
                    onPickTime = { pickingTime = true },
                    onDaysChange = { days = it },
                )
            }
            Section(stringResource(Res.string.automation_where)) {
                Box {
                    SettingsRow(
                        icon = if (project != null) "folder" else "workspaces",
                        title = project ?: stringResource(Res.string.automation_project_whole),
                        subtitle = stringResource(if (project != null) Res.string.automation_project_kept else Res.string.automation_project_pick),
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
                            listOf(MenuAction(stringResource(Res.string.automation_project_whole), "workspaces", { project = null; error = null })),
                            // A project whose folder is gone stays pickable so it isn't lost silently.
                            (state.projects + listOfNotNull(project).filter { it !in state.projects }).map { folder ->
                                MenuAction(folder, "folder", { project = folder; error = null })
                            },
                        ).filter { it.isNotEmpty() },
                    )
                }
            }
            SettingsSection(
                title = stringResource(Res.string.automation_tools),
                description = stringResource(Res.string.automation_tools_hint),
            ) {
                state.tools.forEachIndexed { index, choice ->
                    val checked = tools.containsAll(choice.toolIds)
                    val ask = platform.requestCapabilities
                    SettingsToggleRow(
                        icon = choice.icon,
                        title = choice.labelFor(project),
                        subtitle = null,
                        checked = checked,
                        onCheckedChange = { on ->
                            tools = if (on) tools + choice.toolIds else tools - choice.toolIds.toSet()
                            // A row that needs a permission (reading health data while closed) asks for it now, and goes
                            // back off if it isn't granted.
                            if (on && choice.capabilities.isNotEmpty() && ask != null) {
                                ask(choice.capabilities) { granted -> if (!granted) tools = tools - choice.toolIds.toSet() }
                            }
                        },
                        shape = segmentedShape(index, state.tools.size),
                        // Where the device can't ask Android for what a row needs, it can only be switched off here.
                        enabled = !saving && (checked || choice.capabilities.isEmpty() || ask != null),
                    )
                }
            }
            SupportingNote(
                "notifications",
                name.trim().takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.automation_runs_note, it) }
                    ?: stringResource(Res.string.automation_runs_note_unnamed),
            )
        }
    }
    if (pickingTime) {
        val picker = rememberTimePickerState(minuteOfDay / MINUTES_PER_HOUR, minuteOfDay % MINUTES_PER_HOUR, is24Hour = uses24HourClock())
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            text = { TimePicker(picker) },
            confirmButton = {
                TextButton(onClick = {
                    minuteOfDay = picker.hour * MINUTES_PER_HOUR + picker.minute
                    pickingTime = false
                }) { Text(stringResource(Res.string.automation_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text(stringResource(Res.string.automation_cancel)) } },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(Res.string.automation_delete_title, initial.name)) },
            text = { Text(stringResource(Res.string.automation_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    host.act(AutomationAction.Delete(initial.id))
                    onClose()
                }) { Text(stringResource(Res.string.automation_delete), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(Res.string.automation_cancel)) } },
        )
    }
}

/** Why the phone wouldn't store an automation, in the user's words. */
@Composable
private fun issueTexts(state: AutomationsState): Map<AutomationIssue, String> = mapOf(
    AutomationIssue.NameEmpty to stringResource(Res.string.automation_error_name_empty),
    AutomationIssue.NameTooLong to pluralStringResource(Res.plurals.automation_error_name_long, state.maxNameChars, state.maxNameChars),
    AutomationIssue.PromptEmpty to stringResource(Res.string.automation_error_prompt_empty),
    AutomationIssue.PromptTooLong to pluralStringResource(Res.plurals.automation_error_prompt_long, state.maxPromptChars, state.maxPromptChars),
    AutomationIssue.TooMany to pluralStringResource(Res.plurals.automation_error_too_many, state.maxAutomations, state.maxAutomations),
    AutomationIssue.ProjectInvalid to stringResource(Res.string.automation_error_project),
    AutomationIssue.ToolNotAllowed to stringResource(Res.string.automation_error_tool),
)

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
private fun LastRun(automation: AutomationEntry, status: AutomationStatus, lastRun: String?, enabled: Boolean, onRunNow: () -> Unit, onOpenChat: () -> Unit) {
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
                    lastRun?.let { stringResource(Res.string.automation_last_run, it) } ?: stringResource(Res.string.automation_never_run),
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
                Text(stringResource(Res.string.automation_run_now))
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
                    Text(stringResource(Res.string.automation_open_chat))
                }
            }
        }
    }
}

/** The time, large, to tap and change; the days as seven buttons; and when it next runs. */
@Composable
private fun When(minuteOfDay: Int, days: Set<Int>, summary: String, enabled: Boolean, onPickTime: () -> Unit, onDaysChange: (Set<Int>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val time = formatTime(minuteOfDay)
    val changeTime = stringResource(Res.string.automation_change_time, time)
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
            WEEK.forEach { day ->
                val on = day in days
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
                            if (checked || days.size > 1) {
                                haptics.perform(WhittleHaptic.Select)
                                onDaysChange(if (checked) days + day else days - day)
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

/** The ideas still worth offering: those the user hasn't made yet, matched by name. */
fun unusedIdeas(ideas: List<AutomationIdea>, automations: List<AutomationEntry>): List<AutomationIdea> =
    ideas.filter { idea -> automations.none { it.name.trim().equals(idea.name, ignoreCase = true) } }

/** The ISO weekdays, Monday first. */
private val WEEK = (1..7).toList()

/** How tall the instruction field starts, in lines. */
private const val PROMPT_LINES = 4
