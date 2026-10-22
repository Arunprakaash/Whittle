package app.whittle.settings

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import app.whittle.design.components.BottomActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.agent.Memory
import app.whittle.design.components.MenuAction
import app.whittle.design.components.ScreenHeading
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WelcomeMascot
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.observability.posthog.userContent
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Everything Whittle remembers about the user, newest first and grouped by when it was saved, with how much room is
 * left. Add is the floating button; tapping one opens [MemoryEditor]; Forget everything is in the top bar's menu.
 */
@Composable
fun MemoryScreen(graph: AppGraph, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<MemoryDraft?>(null) }
    val current = editing
    if (current != null) {
        MemoryEditor(graph, current, onClose = { editing = null })
    } else {
        MemoryList(graph, onBack, onOpen = { editing = it })
    }
}

/** What the editor opens on: a saved memory, or the words a new one starts with. */
data class MemoryDraft(val memory: Memory?, val text: String)

@Composable
private fun MemoryList(graph: AppGraph, onBack: () -> Unit, onOpen: (MemoryDraft) -> Unit) {
    BackHandler(onBack = onBack)
    val memories by graph.memory.all.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val policy = graph.memory.policy
    var showingMenu by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    val full = memories.size >= policy.maxItems
    val now = graph.clock.nowMillis()
    val zone = ZoneId.systemDefault()
    val newestFirst = memories.sortedByDescending { it.savedAtMillis }
    val (today, earlier) = newestFirst.partition { isToday(it.savedAtMillis, zone, now) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TopBarButton("arrow_back", stringResource(R.string.settings_back), onBack)
                if (memories.isNotEmpty()) {
                    Box {
                        TopBarButton("more_vert", stringResource(R.string.memory_more), { showingMenu = true })
                        WhittleMenu(
                            expanded = showingMenu,
                            onDismiss = { showingMenu = false },
                            groups = listOf(listOf(MenuAction(stringResource(R.string.memory_clear), "delete", { confirmingClear = true }, destructive = true))),
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!full) BottomActionButton("add", stringResource(R.string.memory_add), { onOpen(MemoryDraft(null, "")) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = WhittleSpace.l, end = WhittleSpace.l, top = WhittleSpace.s, bottom = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            item {
                Column(
                    Modifier.padding(start = WhittleSpace.s, end = WhittleSpace.s, bottom = WhittleSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
                ) {
                    ScreenHeading(stringResource(R.string.memory_heading), stringResource(R.string.memory_intro))
                    if (memories.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                            LinearProgressIndicator(progress = { memories.size.toFloat() / policy.maxItems }, modifier = Modifier.fillMaxWidth())
                            Text(
                                pluralStringResource(R.plurals.memory_used, memories.size, memories.size, policy.maxItems),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (memories.isEmpty()) {
                item { EmptyMemory(graph.memoryIdeas) { idea -> onOpen(MemoryDraft(null, idea.start)) } }
            }
            listOf(R.string.memory_today to today, R.string.memory_earlier to earlier).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
                item(key = title) {
                    Text(
                        stringResource(title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = WhittleSpace.m, top = WhittleSpace.l, bottom = WhittleSpace.s),
                    )
                }
                itemsIndexed(group, key = { _, memory -> memory.id }) { index, memory ->
                    MemoryRow(
                        text = memory.text,
                        meta = savedWhen(resources, memory.savedAtMillis, zone, now),
                        shape = segmentedShape(index, group.size),
                        onClick = { onOpen(MemoryDraft(memory, memory.text)) },
                    )
                }
            }
            if (full) {
                item { SupportingNote("info", stringResource(R.string.memory_full), Modifier.padding(top = WhittleSpace.l)) }
            }
        }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            icon = { WhittleIcon("delete", tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.memory_clear_title)) },
            text = { Text(pluralStringResource(R.plurals.memory_clear_body, memories.size, memories.size)) },
            confirmButton = {
                TextButton(onClick = { confirmingClear = false; graph.appScope.launch { graph.memory.clear() } }) {
                    Text(stringResource(R.string.memory_clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(R.string.memory_cancel)) } },
        )
    }
}

/** Nothing yet: what memory is for, how it fills, and a few kinds of thing to start with. */
@Composable
private fun EmptyMemory(ideas: List<MemoryIdea>, onIdea: (MemoryIdea) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = WhittleSpace.xxxl), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl)) {
        Column(Modifier.padding(horizontal = WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl)) {
            WelcomeMascot()
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                Text(stringResource(R.string.memory_empty_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.memory_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ideas.isNotEmpty()) {
            SettingsSection(title = stringResource(R.string.memory_ideas)) {
                ideas.forEachIndexed { index, idea ->
                    SettingsRow(
                        icon = idea.icon,
                        title = idea.title,
                        subtitle = idea.example,
                        shape = segmentedShape(index, ideas.size),
                        onClick = { onIdea(idea) },
                        trailing = { WhittleIcon("add", size = WhittleSize.Icon.l, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
            }
        }
    }
}

/** One memory: the fact, and when it was saved. */
@Composable
private fun MemoryRow(text: String, meta: String, shape: Shape, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onClick() }
            .padding(start = WhittleSpace.xl, end = WhittleSpace.m, top = WhittleSpace.m, bottom = WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.userContent())
            Text(meta, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        WhittleIcon("chevron_right", size = WhittleSize.Icon.l, tint = colors.onSurfaceVariant)
    }
}

private fun isToday(millis: Long, zone: ZoneId, nowMillis: Long): Boolean =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

/** When a memory was saved, briefly: the time today, "Yesterday", the weekday this week, else the date. */
internal fun savedWhen(resources: Resources, millis: Long, zone: ZoneId, nowMillis: Long): String {
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val day = at.toLocalDate()
    return when {
        day == today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(at)
        day == today.minusDays(1) -> resources.getString(R.string.memory_yesterday)
        day.isAfter(today.minusDays(DAYS_SHOWN_BY_NAME)) -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(at)
    }
}

/** Within this many days, a memory's date is shown as its weekday. */
private const val DAYS_SHOWN_BY_NAME = 7L
