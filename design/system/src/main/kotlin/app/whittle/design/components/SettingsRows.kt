package app.whittle.design.components

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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

/**
 * A titled group of rows, joined like the chat list: large outer corners, small ones between rows. [description] goes
 * above the rows and [footer] below them; keep either to one short line, as the rows say what they are.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    footer: String? = null,
    rows: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = WhittleSpace.m),
        )
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = WhittleSpace.m),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline), content = rows)
        footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = WhittleSpace.m),
            )
        }
    }
}

/** One setting: icon, what it is, a line with its state when it has one, and whatever it ends with (a value, a chevron). */
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
    /** A website whose logo stands in for [icon] once it loads (a connected service, say). */
    logoHost: String? = null,
    /** The row's state is still being read: a skeleton line stands where [subtitle] will go. */
    loading: Boolean = false,
) {
    val haptics = WhittleTheme.haptics
    val action = onClick?.takeIf { enabled }
    SettingsRowLayout(
        icon = icon,
        logoHost = logoHost,
        loading = loading,
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

/** A setting with a few choices: the title above, the choices across the full width below it. */
@Composable
fun SettingsChoiceRow(
    icon: String,
    title: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    choices: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, shape)
            .padding(WhittleSpace.m)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        SettingsRowLayout(
            icon = icon,
            title = title,
            subtitle = null,
            destructive = false,
            enabled = true,
            modifier = Modifier,
            background = RectangleShape,
            trailing = null,
            padded = false,
        )
        choices()
    }
}

@Composable
private fun SettingsRowLayout(
    icon: String,
    logoHost: String? = null,
    loading: Boolean = false,
    title: String,
    subtitle: String?,
    destructive: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    background: Shape,
    trailing: (@Composable () -> Unit)?,
    /** False inside a container that pads and colours the row itself. */
    padded: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val (tile, onTile) = if (destructive) colors.errorContainer to colors.onErrorContainer else colors.secondaryContainer to colors.onSecondaryContainer
    Row(
        modifier = if (padded) {
            modifier
                .fillMaxWidth()
                .heightIn(min = WhittleSize.Tile.xl)
                .background(colors.surfaceContainerLow, background)
                .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.m)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
        } else {
            modifier.fillMaxWidth()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        val symbol = @Composable {
            Box(Modifier.size(WhittleSize.Tile.s).background(tile, CircleShape), contentAlignment = Alignment.Center) {
                WhittleIcon(icon, size = WhittleSize.Icon.m, tint = onTile)
            }
        }
        if (logoHost != null) SiteIcon(logoHost, WhittleSize.Tile.s, WhittleSize.Icon.m, fallback = symbol) else symbol()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (destructive) colors.error else colors.onSurface)
            if (loading) {
                SkeletonBlock(Modifier.padding(vertical = WhittleSpace.hairline).width(WhittleSize.Tile.xl * 2).height(WhittleSpace.m))
            } else {
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            }
        }
        trailing?.invoke()
    }
}

private const val DISABLED_ALPHA = 0.45f
