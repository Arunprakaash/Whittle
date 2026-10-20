package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

enum class StepStatus { Running, Done, Failed, Blocked, Stopped }

/** One tool call as the user sees it: what was done, to what, and the result. Never JSON. */
@Immutable
data class StepUi(
    val id: String,
    val icon: String,
    val label: String,
    val detail: String?,
    val status: StepStatus,
)

/** Consecutive steps as one segmented group: large outer corners, small corners between rows. */
@Composable
fun StepGroup(steps: List<StepUi>, modifier: Modifier = Modifier, onStepClick: ((StepUi) -> Unit)? = null) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        steps.forEachIndexed { index, step ->
            StepRow(step, segmentedShape(index, steps.size), onStepClick)
        }
    }
}

@Composable
private fun StepRow(step: StepUi, shape: Shape, onClick: ((StepUi) -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val (iconContainer, iconContent) = when (step.status) {
        StepStatus.Failed -> colors.errorContainer to colors.onErrorContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow, shape)
            .then(if (onClick != null) Modifier.clickable { onClick(step) } else Modifier)
            .padding(start = WhittleSpace.s, top = WhittleSpace.s, end = WhittleSpace.m, bottom = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(Modifier.size(WhittleSize.Tile.s).background(iconContainer, CircleShape), contentAlignment = Alignment.Center) {
            WhittleIcon(if (step.status == StepStatus.Failed) "error" else step.icon, size = WhittleSize.Icon.m, tint = iconContent)
        }
        Column(Modifier.weight(1f)) {
            val labelStyle = MaterialTheme.typography.titleSmall
            Text(
                text = step.label,
                style = if (step.status == StepStatus.Running) {
                    labelStyle.copy(brush = rememberShimmerBrush(colors.onSurfaceVariant, colors.onSurface))
                } else {
                    labelStyle
                },
                color = if (step.status == StepStatus.Blocked || step.status == StepStatus.Stopped) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            step.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2) }
        }
        Box(Modifier.size(WhittleSize.Icon.xl), contentAlignment = Alignment.Center) {
            when (step.status) {
                StepStatus.Running -> CircularProgressIndicator(Modifier.size(WhittleSize.Icon.s), strokeWidth = SPINNER_STROKE)
                StepStatus.Done -> WhittleIcon("check", size = WhittleSize.Icon.m, tint = colors.primary)
                StepStatus.Failed -> WhittleIcon("refresh", size = WhittleSize.Icon.m, tint = colors.error)
                StepStatus.Blocked -> WhittleIcon("lock", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant)
                StepStatus.Stopped -> WhittleIcon("stop", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant)
            }
        }
    }
}

private val SPINNER_STROKE = 2.dp
