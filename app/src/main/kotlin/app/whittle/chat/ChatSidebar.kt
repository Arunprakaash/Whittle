package app.whittle.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
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

/** The chat list: New chat on top, then saved chats newest first. Deleting asks first; it can't be undone. */
@Composable
fun ChatSidebar(
    chats: List<ChatListItem>,
    current: ChatId?,
    onNewChat: () -> Unit,
    onOpen: (ChatId) -> Unit,
    onDelete: (ChatId) -> Unit,
) {
    val haptics = WhittleTheme.haptics
    var confirmDelete by remember { mutableStateOf<ChatListItem?>(null) }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = WhittleSpace.s))
            FilledTonalButton(
                onClick = { haptics.perform(WhittleHaptic.Tap); onNewChat() },
                modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.default),
            ) {
                WhittleIcon("edit_square", size = WhittleSize.Icon.m)
                Text(stringResource(R.string.sidebar_new_chat), Modifier.padding(start = WhittleSpace.s))
            }
            Text(
                stringResource(if (chats.isEmpty()) R.string.sidebar_empty else R.string.sidebar_recent),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = WhittleSpace.s),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                itemsIndexed(chats, key = { _, chat -> chat.id.value }) { index, chat ->
                    ChatRow(
                        chat = chat,
                        selected = chat.id == current,
                        shape = segmentedShape(index, chats.size),
                        onOpen = { haptics.perform(WhittleHaptic.Select); onOpen(chat.id) },
                        onDelete = { confirmDelete = chat },
                    )
                }
            }
        }
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

/** One line per chat, styled like the model list. Long-press (or the accessibility action) offers Delete. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: ChatListItem, selected: Boolean, shape: Shape, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    var menuOpen by remember { mutableStateOf(false) }
    val deleteLabel = stringResource(R.string.sidebar_delete)
    Box {
        Text(
            text = chat.title ?: stringResource(R.string.sidebar_untitled),
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { haptics.perform(WhittleHaptic.Select); menuOpen = true },
                )
                .semantics {
                    this.selected = selected
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                }
                .padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(deleteLabel) },
                leadingIcon = { WhittleIcon("delete", size = WhittleSize.Icon.m) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}
