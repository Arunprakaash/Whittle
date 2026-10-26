package app.whittle.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
import app.whittle.design.components.WhittleIcon
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** The first chat's things to try: each a row with its icon, a short title and the words it sends. */
@Composable
fun Starters(starters: List<Suggestion>, onStart: (String) -> Unit) {
    if (starters.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Column(Modifier.padding(top = WhittleSpace.l), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        Text(stringResource(Res.string.starters_title), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        starters.forEach { starter ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(WhittleRadius.l))
                    .background(colors.surfaceContainerLow)
                    .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onStart(starter.prompt) }
                    .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                WhittleIcon(starter.icon, size = WhittleSize.Icon.m, tint = colors.primary)
                Column {
                    Text(starter.title, style = MaterialTheme.typography.labelLarge)
                    Text(starter.prompt, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}
