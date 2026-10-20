package app.whittle.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleMotion
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.tokens.WhittleTextStyles

/** The agent's plan as the status card shows it: "2 of 5" and the steps. */
@Immutable
data class StatusPlanUi(val progress: String, val steps: List<PlanStepUi>)

/**
 * Pinned above the composer while a run works: agent dot, what it's doing now, elapsed time and Stop.
 * When the agent made a plan, the card also carries it: tap to unfold the checklist above the line.
 */
@Composable
fun StatusLine(
    state: AgentState,
    verb: String,
    elapsed: String,
    stopLabel: String,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    plan: StatusPlanUi? = null,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    var expanded by rememberSaveable { mutableStateOf(false) }
    val open = expanded && plan != null
    val chevron by animateFloatAsState(if (open) HALF_TURN else 0f, WhittleMotion.spatialDefault.spec(), label = "planChevron")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (open) WhittleRadius.xl else WhittleRadius.full))
            .background(colors.surfaceContainerHigh)
            .animateContentSize(WhittleMotion.spatialDefault.spec()),
    ) {
        AnimatedVisibility(open) {
            Column(
                Modifier.padding(start = WhittleSpace.m, end = WhittleSpace.m, top = WhittleSpace.m),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
            ) {
                plan?.steps?.forEach { PlanStepRow(it) }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(WhittleSize.statusLine)
                .then(
                    if (plan != null) Modifier.clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); expanded = !expanded } else Modifier,
                )
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
            if (plan != null) {
                Text(plan.progress, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                WhittleIcon("expand_less", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(chevron))
            } else {
                Text(elapsed, style = WhittleTextStyles.monoTimer, color = colors.onSurfaceVariant)
            }
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
}

private const val HALF_TURN = 180f
