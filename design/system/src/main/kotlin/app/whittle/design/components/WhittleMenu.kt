package app.whittle.design.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize

/** One menu action. [destructive] ones (delete) are drawn in the error colour. */
data class MenuAction(
    val label: String,
    val icon: String,
    val onClick: () -> Unit,
    val iconFilled: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * An M3 Expressive menu: actions in rounded groups, each item shaped by its place in its group, groups
 * spaced apart. Put destructive actions in a group of their own. Choosing an action closes the menu.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WhittleMenu(expanded: Boolean, onDismiss: () -> Unit, groups: List<List<MenuAction>>) {
    val haptics = WhittleTheme.haptics
    val error = MaterialTheme.colorScheme.error
    DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismiss) {
        groups.forEachIndexed { groupIndex, actions ->
            DropdownMenuGroup(shapes = MenuDefaults.groupShape(groupIndex, groups.size)) {
                actions.forEachIndexed { index, action ->
                    DropdownMenuItem(
                        onClick = {
                            haptics.perform(if (action.destructive) WhittleHaptic.Reject else WhittleHaptic.Select)
                            onDismiss()
                            action.onClick()
                        },
                        text = { Text(action.label) },
                        shape = MenuDefaults.itemShape(index, actions.size).shape,
                        leadingIcon = { WhittleIcon(action.icon, fill = action.iconFilled, size = WhittleSize.Icon.l) },
                        enabled = action.enabled,
                        colors = if (action.destructive) MenuDefaults.itemColors(textColor = error, leadingIconColor = error) else MenuDefaults.itemColors(),
                    )
                }
            }
            if (groupIndex < groups.lastIndex) Spacer(Modifier.height(MenuDefaults.GroupSpacing))
        }
    }
}
