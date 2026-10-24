package app.whittle.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.R
import app.whittle.design.components.WhittleIcon
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.settings.AppIdea

/** A new chat's things to build, as chips like the projects to carry on; tapping one fills the composer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppIdeas(ideas: List<AppIdea>, onPick: (String) -> Unit) {
    if (ideas.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Column(Modifier.padding(top = WhittleSpace.l), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        Text(stringResource(R.string.app_ideas_title), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            ideas.forEach { idea ->
                Row(
                    Modifier
                        .height(WhittleSize.Button.small)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerLow)
                        .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onPick(idea.prompt) }
                        .padding(start = WhittleSpace.m, end = WhittleSpace.l),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
                ) {
                    WhittleIcon(idea.icon, size = WhittleSize.Icon.m, tint = colors.primary)
                    Text(idea.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
