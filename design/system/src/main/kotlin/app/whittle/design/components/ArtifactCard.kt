package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** Something the agent built, in the thread: what it is, and a way in. */
@Composable
fun ArtifactCard(title: String, kind: String, openLabel: String, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WhittleRadius.xl))
            .background(colors.surfaceContainerLow)
            .clickable(role = Role.Button, onClickLabel = openLabel) { haptics.perform(WhittleHaptic.Tap); onOpen() }
            .padding(WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(
            Modifier.size(WhittleSize.Tile.l).background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.m)),
            contentAlignment = Alignment.Center,
        ) {
            WhittleIcon("web_asset", size = WhittleSize.Icon.xl, tint = colors.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(kind, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Box(
            Modifier.clip(RoundedCornerShape(WhittleRadius.full)).background(colors.secondaryContainer).padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.s),
        ) {
            Text(openLabel, style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer)
        }
    }
}

/** One way to open something elsewhere, for [OpenInRow]. */
@Immutable
data class OpenInAction(val icon: String, val label: String, val onClick: () -> Unit)

/** "Open in" choices as one connected group: full screen, browser, share. */
@Composable
fun OpenInRow(actions: List<OpenInAction>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        actions.forEachIndexed { index, action ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(WhittleSize.Tile.xl)
                    .clip(connectedShape(index, actions.size))
                    .background(colors.surfaceContainerHigh)
                    .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); action.onClick() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                WhittleIcon(action.icon, size = WhittleSize.Icon.l, tint = colors.onSurface)
                Text(action.label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}
