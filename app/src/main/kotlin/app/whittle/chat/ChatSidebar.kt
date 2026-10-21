package app.whittle.chat

import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    onOpenSettings: () -> Unit,
    appCount: Int,
    onOpenApps: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val haptics = WhittleTheme.haptics
    val groups = remember(chats, maxChats) {
        ChatSections.sidebar(ChatSections.group(chats, System.currentTimeMillis(), ZoneId.systemDefault()), maxChats)
    }
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxHeight().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.l)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = WhittleSpace.s))
            // Material 3 Expressive medium button: taller, fully rounded, morphs on press, filled icon.
            val height = ButtonDefaults.MediumContainerHeight
            Button(
                onClick = { haptics.perform(WhittleHaptic.Tap); onNewChat() },
                shapes = ButtonDefaults.shapes(),
                contentPadding = ButtonDefaults.contentPaddingFor(height),
                modifier = Modifier.fillMaxWidth().padding(top = WhittleSpace.m).height(height),
            ) {
                WhittleIcon("edit_square", size = ButtonDefaults.iconSizeFor(height))
                Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
                Text(stringResource(R.string.sidebar_new_chat), style = ButtonDefaults.textStyleFor(height))
            }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.titleSmall) },
                icon = { WhittleIcon("deployed_code", size = WhittleSize.Icon.xl) },
                badge = { if (appCount > 0) Text(appCount.toString(), style = MaterialTheme.typography.labelLarge) },
                selected = false,
                onClick = { haptics.perform(WhittleHaptic.Tap); onOpenApps() },
                modifier = Modifier.padding(top = WhittleSpace.s),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.files_title), style = MaterialTheme.typography.titleSmall) },
                icon = { WhittleIcon("folder", size = WhittleSize.Icon.xl) },
                selected = false,
                onClick = { haptics.perform(WhittleHaptic.Tap); onOpenFiles() },
            )
            if (chats.isEmpty()) {
                Text(
                    stringResource(R.string.sidebar_empty),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = WhittleSpace.s, top = WhittleSpace.l),
                )
            }
            // The list takes the room between New chat and Settings, which stays at the bottom.
            Box(Modifier.weight(1f)) {
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
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleSmall) },
                icon = { WhittleIcon("settings", size = WhittleSize.Icon.xl) },
                selected = false,
                onClick = { haptics.perform(WhittleHaptic.Tap); onOpenSettings() },
                modifier = Modifier.padding(top = WhittleSpace.s),
            )
        }
    }
}
