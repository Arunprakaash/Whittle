package app.whittle.chat.ui

import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.MenuAction
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/**
 * Chats in sections (Pinned, Today, ...) as grouped one-line rows; a chat whose task is [working] shows a spinner.
 * Long-press a chat to pin or delete it; deleting asks first because it can't be undone. Shared by the sidebar and
 * the Chats page.
 */
@Composable
fun ChatGroupList(
    groups: List<ChatGroup>,
    current: String?,
    working: Set<String>,
    onOpen: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    footer: LazyListScope.() -> Unit = {},
) {
    val haptics = WhittleTheme.haptics
    var confirmDelete by remember { mutableStateOf<ChatEntry?>(null) }
    LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        groups.forEach { group ->
            item(key = "section-${group.section}") { SectionHeader(group.section) }
            itemsIndexed(group.chats, key = { _, chat -> chat.id }) { index, chat ->
                ChatRow(
                    chat = chat,
                    selected = chat.id == current,
                    working = chat.id in working,
                    shape = segmentedShape(index, group.chats.size),
                    onOpen = { haptics.perform(WhittleHaptic.Select); onOpen(chat.id) },
                    onPin = { onPin(chat.id, !chat.pinned) },
                    onDelete = { confirmDelete = chat },
                )
            }
        }
        footer()
    }
    confirmDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(Res.string.sidebar_delete_title)) },
            text = { Text(stringResource(Res.string.sidebar_delete_body)) },
            confirmButton = {
                TextButton(onClick = { haptics.perform(WhittleHaptic.Reject); onDelete(chat.id); confirmDelete = null }) {
                    Text(stringResource(Res.string.sidebar_delete_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(Res.string.sidebar_delete_cancel)) } },
        )
    }
}

@Composable
private fun SectionHeader(section: ChatSection) {
    Text(
        text = sectionTitle(section),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = WhittleSpace.s, top = WhittleSpace.l, bottom = WhittleSpace.s)
            .semantics { heading() },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: ChatEntry, selected: Boolean, working: Boolean, shape: Shape, onOpen: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    var menuOpen by remember { mutableStateOf(false) }
    val pinLabel = stringResource(if (chat.pinned) Res.string.chats_unpin else Res.string.chats_pin)
    val deleteLabel = stringResource(Res.string.sidebar_delete)
    val workingLabel = stringResource(Res.string.chats_working)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { haptics.perform(WhittleHaptic.Select); menuOpen = true },
                )
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    if (working) stateDescription = workingLabel
                    customActions = listOf(
                        CustomAccessibilityAction(pinLabel) { onPin(); true },
                        CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                }
                .padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
        ) {
            Text(
                text = chat.title ?: stringResource(Res.string.sidebar_untitled),
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.userContent().weight(1f),
            )
            // The task keeps going in this chat while another is open.
            if (working) CircularProgressIndicator(Modifier.size(WhittleSize.Icon.s), strokeWidth = SPINNER_STROKE)
        }
        WhittleMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            groups = listOf(
                listOf(MenuAction(pinLabel, "push_pin", onPin, iconFilled = chat.pinned)),
                listOf(MenuAction(deleteLabel, "delete", onDelete, destructive = true)),
            ),
        )
    }
}

@Composable
private fun sectionTitle(section: ChatSection): String = stringResource(
    when (section) {
        ChatSection.Pinned -> Res.string.chats_section_pinned
        ChatSection.Today -> Res.string.chats_section_today
        ChatSection.Yesterday -> Res.string.chats_section_yesterday
        ChatSection.Previous7Days -> Res.string.chats_section_week
        ChatSection.Previous30Days -> Res.string.chats_section_month
        ChatSection.Older -> Res.string.chats_section_older
    },
)

/** Matches the step and plan spinners. */
private val SPINNER_STROKE = 2.dp
