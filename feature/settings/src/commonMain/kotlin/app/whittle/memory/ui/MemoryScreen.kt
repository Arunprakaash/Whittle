package app.whittle.memory.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import app.whittle.design.components.ReadingLazyColumn
import app.whittle.design.components.ReadingScrollColumn
import app.whittle.design.components.BottomActionButton
import app.whittle.design.components.MenuAction
import app.whittle.design.components.ScreenHeading
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SupportingNote
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WelcomeMascot
import app.whittle.design.components.WhittleFilledField
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
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
 * Everything Whittle remembers about the user, newest first and grouped by when it was saved, with how much room is
 * left. Add is the button at the bottom; tapping one opens the editor; Forget everything is in the top bar's menu.
 */
@Composable
fun MemoryScreen(host: MemoryHost, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<MemoryDraft?>(null) }
    val current = editing
    if (current != null) {
        MemoryEditor(host, current, onClose = { editing = null })
    } else {
        MemoryList(host, onBack, onOpen = { editing = it })
    }
}

/** What the editor opens on: a saved memory, or the words a new one starts with. */
private data class MemoryDraft(val memory: MemoryEntry?, val text: String)

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
private fun MemoryList(host: MemoryHost, onBack: () -> Unit, onOpen: (MemoryDraft) -> Unit) {
    BackHandler(enabled = true, onBack = onBack)
    val state by host.state.collectAsState()
    val memories = state.memories
    var showingMenu by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    val full = state.maxItems > 0 && memories.size >= state.maxItems
    val now = nowMillis()
    val (today, earlier) = memories.sortedByDescending { it.savedAtMillis }.partition { isToday(it.savedAtMillis, now) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopBarRow {
                TopBarButton("arrow_back", stringResource(Res.string.settings_back), onBack)
                Box(Modifier.weight(1f))
                if (memories.isNotEmpty()) {
                    Box {
                        TopBarButton("more_vert", stringResource(Res.string.memory_more), { showingMenu = true })
                        WhittleMenu(
                            expanded = showingMenu,
                            onDismiss = { showingMenu = false },
                            groups = listOf(listOf(MenuAction(stringResource(Res.string.memory_clear), "delete", { confirmingClear = true }, destructive = true))),
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!full) BottomActionButton("add", stringResource(Res.string.memory_add), { onOpen(MemoryDraft(null, "")) })
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
                Column(
                    Modifier.padding(start = WhittleSpace.s, end = WhittleSpace.s, bottom = WhittleSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
                ) {
                    ScreenHeading(stringResource(Res.string.memory_heading), stringResource(Res.string.memory_intro))
                    if (memories.isNotEmpty() && state.maxItems > 0) {
                        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                            LinearProgressIndicator(progress = { memories.size.toFloat() / state.maxItems }, modifier = Modifier.fillMaxWidth())
                            Text(
                                pluralStringResource(Res.plurals.memory_used, memories.size, memories.size, state.maxItems),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (memories.isEmpty()) {
                item { EmptyMemory(state.ideas) { idea -> onOpen(MemoryDraft(null, idea.start)) } }
            }
            listOf(Res.string.memory_today to today, Res.string.memory_earlier to earlier).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
                item(key = title.key) {
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
                        meta = savedWhen(memory.savedAtMillis, now),
                        shape = segmentedShape(index, group.size),
                        onClick = { onOpen(MemoryDraft(memory, memory.text)) },
                    )
                }
            }
            if (full) {
                item { SupportingNote("info", stringResource(Res.string.memory_full), Modifier.padding(top = WhittleSpace.l)) }
            }
        }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            icon = { WhittleIcon("delete", tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(Res.string.memory_clear_title)) },
            text = { Text(pluralStringResource(Res.plurals.memory_clear_body, memories.size, memories.size)) },
            confirmButton = {
                TextButton(onClick = { confirmingClear = false; host.act(MemoryAction.Clear) }) {
                    Text(stringResource(Res.string.memory_clear_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(Res.string.memory_cancel)) } },
        )
    }
}

/** Nothing yet: what memory is for, how it fills, and a few kinds of thing to start with. */
@Composable
private fun EmptyMemory(ideas: List<MemoryIdeaEntry>, onIdea: (MemoryIdeaEntry) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = WhittleSpace.xxxl), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xxl)) {
        Column(Modifier.padding(horizontal = WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl)) {
            WelcomeMascot()
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                Text(stringResource(Res.string.memory_empty_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.memory_empty_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (ideas.isNotEmpty()) {
            SettingsSection(title = stringResource(Res.string.memory_ideas)) {
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

/**
 * Writes a new memory or changes one, full screen: close and Save at the top, one field with a counter, when it was
 * saved and that it stays on the phone. Forget this is in the top bar's menu.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MemoryEditor(host: MemoryHost, draft: MemoryDraft, onClose: () -> Unit) {
    BackHandler(enabled = true, onBack = onClose)
    val state by host.state.collectAsState()
    val saved = draft.memory
    var text by remember { mutableStateOf(draft.text) }
    var saving by remember { mutableStateOf(false) }
    var issue by remember { mutableStateOf<MemoryIssue?>(null) }
    var showingMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val canSave = !saving && text.isNotBlank() && text.trim() != saved?.text

    fun save() {
        saving = true
        issue = null
        scope.launch {
            issue = host.save(saved?.id, text)
            saving = false
            if (issue == null) onClose()
        }
    }

    Scaffold(
        containerColor = colors.surface,
        topBar = {
            TopBarRow(Modifier.background(if (scrolled) colors.surfaceContainerLow else colors.surface)) {
                TopBarButton("close", stringResource(Res.string.memory_close), onClose)
                Text(
                    stringResource(if (saved == null) Res.string.memory_new else Res.string.memory_edit),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { haptics.perform(WhittleHaptic.Approve); save() },
                    enabled = canSave,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.height(WhittleSize.Button.small),
                ) { Text(stringResource(Res.string.memory_save)) }
                if (saved != null) {
                    Box {
                        TopBarButton("more_vert", stringResource(Res.string.memory_more), { showingMenu = true })
                        WhittleMenu(
                            expanded = showingMenu,
                            onDismiss = { showingMenu = false },
                            groups = listOf(
                                listOf(
                                    MenuAction(stringResource(Res.string.memory_forget), "delete", {
                                        host.act(MemoryAction.Forget(saved.id))
                                        onClose()
                                    }, destructive = true),
                                ),
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
            WhittleFilledField(
                value = text,
                onValueChange = { text = if (state.maxItemChars > 0) it.take(state.maxItemChars) else it; issue = null },
                label = stringResource(Res.string.memory_field),
                minLines = FIELD_LINES,
                enabled = !saving,
                hint = stringResource(Res.string.memory_field_hint),
                counter = stringResource(Res.string.memory_counter, text.length, state.maxItemChars),
                error = issue?.let { stringResource(if (it == MemoryIssue.Full) Res.string.memory_full else Res.string.memory_save_failed) },
                autoFocus = saved == null,
                modifier = Modifier.userContent(),
            )
            val facts = listOfNotNull(
                saved?.let {
                    Triple("history", stringResource(Res.string.memory_saved_at, savedOn(it.savedAtMillis)), stringResource(Res.string.memory_saved_note))
                },
                Triple("lock", stringResource(Res.string.memory_private), stringResource(Res.string.memory_private_note)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                facts.forEachIndexed { index, (icon, title, subtitle) ->
                    SettingsRow(icon = icon, title = title, subtitle = subtitle, shape = segmentedShape(index, facts.size))
                }
            }
        }
    }
}

/** How tall the field starts, in lines. */
private const val FIELD_LINES = 3
