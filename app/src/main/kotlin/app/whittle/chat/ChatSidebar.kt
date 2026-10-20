package app.whittle.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.whittle.R
import app.whittle.design.components.WhittleIcon
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.storage.chats.ChatId
import java.time.ZoneId

/** New chat, then pinned chats and the last week (capped); everything else is one tap away on the Chats page. */
@Composable
fun ChatSidebar(
    chats: List<ChatListItem>,
    current: ChatId?,
    maxChats: Int,
    onNewChat: () -> Unit,
    onOpen: (ChatId) -> Unit,
    onPin: (ChatId, Boolean) -> Unit,
    onDelete: (ChatId) -> Unit,
    onViewAll: () -> Unit,
) {
    val haptics = WhittleTheme.haptics
    val groups = remember(chats, maxChats) {
        ChatSections.sidebar(ChatSections.group(chats, System.currentTimeMillis(), ZoneId.systemDefault()), maxChats)
    }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.l)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = WhittleSpace.s))
            FilledTonalButton(
                onClick = { haptics.perform(WhittleHaptic.Tap); onNewChat() },
                modifier = Modifier.fillMaxWidth().padding(top = WhittleSpace.m).height(WhittleSize.Button.default),
            ) {
                WhittleIcon("edit_square", size = WhittleSize.Icon.m)
                Text(stringResource(R.string.sidebar_new_chat), Modifier.padding(start = WhittleSpace.s))
            }
            if (chats.isEmpty()) {
                Text(
                    stringResource(R.string.sidebar_empty),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = WhittleSpace.s, top = WhittleSpace.l),
                )
            }
            ChatGroupList(
                groups = groups,
                current = current,
                onOpen = onOpen,
                onPin = onPin,
                onDelete = onDelete,
                footer = {
                    if (chats.isNotEmpty()) {
                        item(key = "view-all") {
                            TextButton(
                                onClick = { haptics.perform(WhittleHaptic.Tap); onViewAll() },
                                modifier = Modifier.padding(top = WhittleSpace.s),
                            ) {
                                Text(stringResource(R.string.sidebar_view_all))
                                WhittleIcon("chevron_right", size = WhittleSize.Icon.m)
                            }
                        }
                    }
                },
            )
        }
    }
}
