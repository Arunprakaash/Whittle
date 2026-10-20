package app.whittle.design.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleTextStyles
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
    /** What exactly ran and what came back, shown in mono when the row is tapped. Null: nothing to show. */
    val inspection: String? = null,
    /** Host of the web page this step reads; its own icon is shown instead of [icon] once loaded. */
    val site: String? = null,
)

/** Consecutive steps as one segmented group: large outer corners, small corners between rows. */
@Composable
fun StepGroup(steps: List<StepUi>, modifier: Modifier = Modifier) {
    val haptics = WhittleTheme.haptics
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        steps.forEachIndexed { index, step ->
            val open = step.id in expanded
            val toggle = step.inspection?.let {
                { _: StepUi ->
                    haptics.perform(WhittleHaptic.Tap)
                    expanded = if (open) expanded - step.id else expanded + step.id
                }
            }
            StepRow(step, segmentedShape(index, steps.size), toggle)
            AnimatedVisibility(open && step.inspection != null) {
                Text(
                    text = step.inspection.orEmpty(),
                    style = WhittleTextStyles.mono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = WhittleSpace.xs)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(WhittleRadius.m))
                        .border(WhittleSpace.hairline / 2, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(WhittleRadius.m))
                        .padding(WhittleSpace.m),
                )
            }
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
        val iconTile: @Composable () -> Unit = {
            Box(Modifier.size(WhittleSize.Tile.s).background(iconContainer, CircleShape), contentAlignment = Alignment.Center) {
                WhittleIcon(if (step.status == StepStatus.Failed) "error" else step.icon, size = WhittleSize.Icon.m, tint = iconContent)
            }
        }
        val site = step.site?.takeIf { step.status != StepStatus.Failed }
        if (site != null) SiteIcon(site, containerSize = WhittleSize.Tile.s, iconSize = WhittleSize.Icon.l, fallback = iconTile) else iconTile()
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
