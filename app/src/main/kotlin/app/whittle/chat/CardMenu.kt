package app.whittle.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.whittle.design.components.MenuAction
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleMenu
import app.whittle.design.tokens.WhittleSize

/** A card's ⋯ button for its rarer actions, so the card shows one main action; nothing when there are none. */
@Composable
fun CardMenu(label: String, actions: List<MenuAction>) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            WhittleIcon("more_vert", contentDescription = label, size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        WhittleMenu(expanded = open, onDismiss = { open = false }, groups = listOf(actions))
    }
}
