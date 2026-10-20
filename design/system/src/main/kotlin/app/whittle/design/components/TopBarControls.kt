package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** A single top bar action on its own disc, such as the button that opens the chat list. */
@Composable
fun TopBarButton(icon: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(WhittleSize.Tile.l)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        TopBarAction(icon, contentDescription, onClick)
    }
}

/** Several top bar actions sharing one pill, such as new chat and the overflow menu. */
@Composable
fun TopBarActionGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .height(WhittleSize.Tile.l)
            .clip(RoundedCornerShape(WhittleRadius.full))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
        content = content,
    )
}

/** One icon action inside a [TopBarButton] or [TopBarActionGroup]; its touch target fills the bar height. */
@Composable
fun TopBarAction(icon: String, contentDescription: String, onClick: () -> Unit) {
    val haptics = WhittleTheme.haptics
    Box(
        modifier = Modifier
            .size(WhittleSize.Tile.l)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = contentDescription) { haptics.perform(WhittleHaptic.Tap); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        WhittleIcon(icon, contentDescription = contentDescription, size = WhittleSize.Icon.xl, tint = MaterialTheme.colorScheme.onSurface)
    }
}
