package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.tokens.WhittleTextStyles

/** Pinned above the composer while a run works: agent dot, what it's doing now, elapsed time and Stop. */
@Composable
fun StatusLine(
    state: AgentState,
    verb: String,
    elapsed: String,
    stopLabel: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(WhittleSize.statusLine)
            .background(colors.surfaceContainerHigh, RoundedCornerShape(WhittleRadius.full))
            .padding(start = WhittleSpace.m, end = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        AgentIndicator(state)
        Text(
            text = verb,
            style = MaterialTheme.typography.labelLarge.copy(brush = rememberShimmerBrush(colors.onSurfaceVariant, colors.primary)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(elapsed, style = WhittleTextStyles.monoTimer, color = colors.onSurfaceVariant)
        FilledTonalButton(
            onClick = { haptics.perform(WhittleHaptic.Reject); onStop() },
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.surfaceContainerHighest, contentColor = colors.onSurface),
            contentPadding = PaddingValues(start = WhittleSpace.s, end = WhittleSpace.m),
            modifier = Modifier.height(WhittleSize.Button.small - WhittleSpace.xs),
        ) {
            WhittleIcon("stop", fill = true, size = WhittleSize.Icon.m)
            Text(stopLabel, modifier = Modifier.padding(start = WhittleSpace.xs), style = MaterialTheme.typography.labelLarge)
        }
    }
}
