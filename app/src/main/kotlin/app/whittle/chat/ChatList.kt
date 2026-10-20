package app.whittle.chat

import app.whittle.design.components.WhittleMenu
import app.whittle.design.components.MenuAction
import app.whittle.observability.posthog.userContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.R
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.storage.chats.ChatId

/**
 * Chats in sections (Pinned, Today, ...) as grouped one-line rows. Long-press a chat to pin or delete it;
 * deleting asks first because it can't be undone. Shared by the sidebar and the Chats page.
 */
@Composable
fun ChatGroupList(
    groups: List<ChatGroup>,
    current: ChatId?,
    onOpen: (ChatId) -> Unit,
    onPin: (ChatId, Boolean) -> Unit,
    onDelete: (ChatId) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    footer: LazyListScope.() -> Unit = {},
) {
    val haptics = WhittleTheme.haptics
    var confirmDelete by remember { mutableStateOf<ChatListItem?>(null) }
    LazyColumn(modifier, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        groups.forEach { group ->
            item(key = "section-${group.section}") { SectionHeader(group.section) }
            itemsIndexed(group.chats, key = { _, chat -> chat.id.value }) { index, chat ->
                ChatRow(
                    chat = chat,
                    selected = chat.id == current,
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
            title = { Text(stringResource(R.string.sidebar_delete_title)) },
            text = { Text(stringResource(R.string.sidebar_delete_body)) },
            confirmButton = {
                TextButton(onClick = { haptics.perform(WhittleHaptic.Reject); onDelete(chat.id); confirmDelete = null }) {
                    Text(stringResource(R.string.sidebar_delete_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.sidebar_delete_cancel)) } },
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
private fun ChatRow(chat: ChatListItem, selected: Boolean, shape: Shape, onOpen: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    var menuOpen by remember { mutableStateOf(false) }
    val pinLabel = stringResource(if (chat.pinned) R.string.chats_unpin else R.string.chats_pin)
    val deleteLabel = stringResource(R.string.sidebar_delete)
    Box {
        Text(
            text = chat.title ?: stringResource(R.string.sidebar_untitled),
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .userContent()
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { haptics.perform(WhittleHaptic.Select); menuOpen = true },
                )
                .semantics {
                    this.selected = selected
                    customActions = listOf(
                        CustomAccessibilityAction(pinLabel) { onPin(); true },
                        CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                    )
                }
                .padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
        )
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
        ChatSection.Pinned -> R.string.chats_section_pinned
        ChatSection.Today -> R.string.chats_section_today
        ChatSection.Yesterday -> R.string.chats_section_yesterday
        ChatSection.Previous7Days -> R.string.chats_section_week
        ChatSection.Previous30Days -> R.string.chats_section_month
        ChatSection.Older -> R.string.chats_section_older
    },
)
