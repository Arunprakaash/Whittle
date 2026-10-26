package app.whittle.chat.ui

import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import app.whittle.design.components.BeaverAvatar
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
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
import app.whittle.design.components.WhittleIcon
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import kotlinx.datetime.TimeZone

/**
 * Projects (the shared workspace), then pinned chats and the last week (capped); everything else is one tap away on the
 * Chats page. At the bottom: the user's own button, which opens Settings, and New chat.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatSidebar(
    chats: List<ChatEntry>,
    current: String?,
    /** Chats with a task working in the background. */
    working: Set<String>,
    maxChats: Int,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    /** Opens the Chats page; null where it isn't here. */
    onViewAll: (() -> Unit)?,
    /** Opens Settings; null where they aren't here. */
    onOpenSettings: (() -> Unit)?,
    /** Draws the user's avatar; until it's made, a plain person icon stands in. */
    avatarSeed: Long?,
    /** Opens the projects; null where they aren't here. */
    onOpenProjects: (() -> Unit)?,
) {
    val haptics = WhittleTheme.haptics
    val colors = MaterialTheme.colorScheme
    val settingsLabel = stringResource(Res.string.settings_title)
    val groups = remember(chats, maxChats) {
        ChatSections.sidebar(ChatSections.group(chats, nowMillis(), TimeZone.currentSystemDefault()), maxChats)
    }
    ModalDrawerSheet(drawerContainerColor = colors.surfaceContainerLow, windowInsets = LocalWindowChrome.current?.insets ?: DrawerDefaults.windowInsets) {
        Column(Modifier.fillMaxHeight().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.l)) {
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = WhittleSpace.s))
            onOpenProjects?.let { open ->
                NavigationDrawerItem(
                    label = { Text(stringResource(Res.string.projects_title), style = MaterialTheme.typography.titleSmall) },
                    icon = { WhittleIcon("folder_open", size = WhittleSize.Icon.xl) },
                    selected = false,
                    onClick = { haptics.perform(WhittleHaptic.Tap); open() },
                    modifier = Modifier.padding(top = WhittleSpace.m),
                )
            }
            HorizontalDivider(Modifier.padding(horizontal = WhittleSpace.s, vertical = WhittleSpace.s), color = colors.outlineVariant)
            if (chats.isEmpty()) {
                Text(
                    stringResource(Res.string.sidebar_empty),
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
                    working = working,
                    onOpen = onOpen,
                    onPin = onPin,
                    onDelete = onDelete,
                    footer = {
                        if (chats.isNotEmpty() && onViewAll != null) {
                            item(key = "view-all") {
                                TextButton(
                                    onClick = { haptics.perform(WhittleHaptic.Tap); onViewAll() },
                                    modifier = Modifier.padding(top = WhittleSpace.s),
                                ) {
                                    Text(stringResource(Res.string.sidebar_view_all))
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
                    onClick = { haptics.perform(WhittleHaptic.Tap); onOpenSettings?.invoke() },
                    enabled = onOpenSettings != null,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(ButtonDefaults.MediumContainerHeight).semantics { contentDescription = settingsLabel },
                ) {
                    if (avatarSeed != null) {
                        BeaverAvatar(avatarSeed, Modifier.fillMaxSize())
                    } else {
                        WhittleIcon("person", fill = true, size = WhittleSize.Icon.xl)
                    }
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
                    Text(stringResource(Res.string.sidebar_new_chat), style = ButtonDefaults.textStyleFor(height))
                }
            }
        }
    }
}
