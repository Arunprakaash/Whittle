package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

enum class PlanStepState { Pending, Active, Done }

@Immutable
data class PlanStepUi(val text: String, val state: PlanStepState)

/** The agent's plan for a task: each step ticked off as it finishes, the current one spinning. */
@Composable
fun PlanChecklist(title: String, steps: List<PlanStepUi>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl))
            .padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            WhittleIcon("checklist", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
        steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                Box(Modifier.size(WhittleSize.Icon.xl), contentAlignment = Alignment.Center) {
                    when (step.state) {
                        PlanStepState.Done -> WhittleIcon("check_circle", fill = true, size = WhittleSize.Icon.l, tint = colors.primary)
                        PlanStepState.Active -> CircularProgressIndicator(Modifier.size(WhittleSize.Icon.m), strokeWidth = SPINNER_STROKE)
                        PlanStepState.Pending -> WhittleIcon("radio_button_unchecked", size = WhittleSize.Icon.l, tint = colors.outline)
                    }
                }
                Text(
                    step.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (step.state) {
                        PlanStepState.Done -> colors.onSurfaceVariant
                        PlanStepState.Active -> colors.onSurface
                        PlanStepState.Pending -> colors.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val SPINNER_STROKE = 2.dp
