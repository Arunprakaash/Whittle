package app.whittle.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** The screen the assistant read, going with the next message: which app it was, and a way to leave it out. */
@Composable
fun ScreenChip(appName: String?, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .height(WhittleSize.Tile.s)
            .clip(CircleShape)
            .background(colors.tertiaryContainer)
            .padding(start = WhittleSpace.s, end = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
    ) {
        WhittleIcon("mobile_screen_share", size = WhittleSize.Icon.m, tint = colors.onTertiaryContainer)
        Text(
            appName?.let { stringResource(R.string.screen_chip, it) } ?: stringResource(R.string.screen_chip_unnamed),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        WhittleIcon(
            "close",
            contentDescription = stringResource(R.string.screen_chip_remove),
            size = WhittleSize.Icon.m,
            tint = colors.onTertiaryContainer,
            modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onRemove).padding(WhittleSpace.xs),
        )
    }
}
