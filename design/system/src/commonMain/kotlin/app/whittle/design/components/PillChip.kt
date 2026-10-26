package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/**
 * A filled pill with an icon and a label, as the chat's project chip is drawn: a computer, a way in. [dim] greys it
 * when what it stands for is off, [working] puts a spinner where the icon goes, and [dot] draws a small dot after the
 * label: pass [Color.Transparent] to keep its place, so the chip's width doesn't change when the dot comes and goes.
 */
@Composable
fun PillChip(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    working: Boolean = false,
    dot: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val content = if (dim) colors.outline else colors.onSurface
    Row(
        modifier
            .height(WhittleSize.Button.small)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onClick() } else Modifier)
            .padding(start = WhittleSpace.m, end = WhittleSpace.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        if (working) {
            CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = WhittleSpace.hairline, color = content)
        } else {
            WhittleIcon(icon, size = WhittleSize.Icon.m, tint = content)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        dot?.let { Box(Modifier.size(WhittleSpace.s).clip(CircleShape).background(it)) }
    }
}
