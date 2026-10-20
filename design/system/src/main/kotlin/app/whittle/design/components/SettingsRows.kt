package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** A titled group of rows, joined like the chat list: large outer corners, small ones between rows. */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, rows: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = WhittleSpace.m),
        )
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline), content = rows)
    }
}

/** One setting: icon, what it is, a line explaining it, and whatever it ends with (a value, a chevron). */
@Composable
fun SettingsRow(
    icon: String,
    title: String,
    subtitle: String?,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val haptics = WhittleTheme.haptics
    val action = onClick?.takeIf { enabled }
    SettingsRowLayout(
        icon = icon,
        title = title,
        subtitle = subtitle,
        destructive = destructive,
        enabled = enabled,
        modifier = modifier
            .clip(shape)
            .then(if (action != null) Modifier.clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); action() } else Modifier),
        background = shape,
        trailing = trailing,
    )
}

/** A setting that is on or off; the whole row toggles it. */
@Composable
fun SettingsToggleRow(
    icon: String,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = WhittleTheme.haptics
    SettingsRowLayout(
        icon = icon,
        title = title,
        subtitle = subtitle,
        destructive = false,
        enabled = enabled,
        modifier = modifier
            .clip(shape)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch) { haptics.perform(WhittleHaptic.Tap); onCheckedChange(it) },
        background = shape,
        trailing = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

@Composable
private fun SettingsRowLayout(
    icon: String,
    title: String,
    subtitle: String?,
    destructive: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    background: Shape,
    trailing: (@Composable () -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val (tile, onTile) = if (destructive) colors.errorContainer to colors.onErrorContainer else colors.secondaryContainer to colors.onSecondaryContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WhittleSize.Tile.xl)
            .background(colors.surfaceContainerLow, background)
            .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.m)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(Modifier.size(WhittleSize.Tile.s).background(tile, CircleShape), contentAlignment = Alignment.Center) {
            WhittleIcon(icon, size = WhittleSize.Icon.m, tint = onTile)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (destructive) colors.error else colors.onSurface)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

private const val DISABLED_ALPHA = 0.45f
