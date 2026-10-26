package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** A single top bar action on its own disc, such as the button that opens the chat list. */
@Composable
fun TopBarButton(icon: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TopBarAction(icon, contentDescription, onClick, shape = CircleShape, modifier = modifier.size(WhittleSize.Tile.l))
}

/**
 * Connected top bar actions, such as new chat and the overflow menu: each its own segment, a hairline
 * apart. Give each [TopBarAction] inside its [connectedShape] and [TopBarGroupAction] width.
 */
@Composable
fun TopBarActionGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        content = content,
    )
}

/** One top bar action on its own [shape]; the shape decides whether it stands alone or joins a group. */
@Composable
fun TopBarAction(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val haptics = WhittleTheme.haptics
    Box(
        modifier = modifier
            .height(WhittleSize.Tile.l)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClickLabel = contentDescription) { haptics.perform(WhittleHaptic.Tap); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        WhittleIcon(icon, contentDescription = contentDescription, size = WhittleSize.Icon.xl, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** Width of an action inside a [TopBarActionGroup]: a little wider than tall, so the group reads as one pill. */
val TopBarGroupAction: Modifier = Modifier.width(WhittleSize.Tile.xl)
