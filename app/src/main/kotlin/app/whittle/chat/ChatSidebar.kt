package app.whittle.chat

import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/**
 * Projects and Apps, then pinned chats and the last week (capped); everything else is one tap away on the
 * Chats page. At the bottom: the user's own button, which opens Settings, and New chat.
 */
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
    onOpenProjects: () -> Unit,
) {
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val settingsLabel = stringResource(R.string.settings_title)
    val groups = remember(chats, maxChats) {
        ChatSections.sidebar(ChatSections.group(chats, System.currentTimeMillis(), ZoneId.systemDefault()), maxChats)
    }
    ModalDrawerSheet(drawerContainerColor = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxHeight().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.l)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = WhittleSpace.s))
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.projects_title), style = MaterialTheme.typography.titleSmall) },
                icon = { WhittleIcon("stacks", size = WhittleSize.Icon.xl) },
                selected = false,
                onClick = { haptics.perform(WhittleHaptic.Tap); onOpenProjects() },
                modifier = Modifier.padding(top = WhittleSpace.m),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.apps_title), style = MaterialTheme.typography.titleSmall) },
                icon = { WhittleIcon("deployed_code", size = WhittleSize.Icon.xl) },
                badge = { if (appCount > 0) Text(appCount.toString(), style = MaterialTheme.typography.labelLarge) },
                selected = false,
                onClick = { haptics.perform(WhittleHaptic.Tap); onOpenApps() },
            )
            HorizontalDivider(Modifier.padding(horizontal = WhittleSpace.s, vertical = WhittleSpace.s), color = colors.outlineVariant)
            if (chats.isEmpty()) {
                Text(
                    stringResource(R.string.sidebar_empty),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = WhittleSpace.s, top = WhittleSpace.s),
                )
            }
            // The list takes the room between the top and the bottom row, which stays put.
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
            Row(
                Modifier.fillMaxWidth().padding(top = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Whittle has no accounts; the user's button is where their settings live.
                FilledTonalIconButton(
                    onClick = { haptics.perform(WhittleHaptic.Tap); onOpenSettings() },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(ButtonDefaults.MediumContainerHeight).semantics { contentDescription = settingsLabel },
                ) {
                    WhittleIcon("person", fill = true, size = WhittleSize.Icon.xl)
                }
                // Material 3 Expressive medium button: taller, fully rounded, morphs on press.
                val height = ButtonDefaults.MediumContainerHeight
                Button(
                    onClick = { haptics.perform(WhittleHaptic.Tap); onNewChat() },
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = ButtonDefaults.contentPaddingFor(height),
                    modifier = Modifier.height(height),
                ) {
                    WhittleIcon("add", size = ButtonDefaults.iconSizeFor(height))
                    Spacer(Modifier.width(ButtonDefaults.iconSpacingFor(height)))
                    Text(stringResource(R.string.sidebar_new_chat), style = ButtonDefaults.textStyleFor(height))
                }
            }
        }
    }
}
