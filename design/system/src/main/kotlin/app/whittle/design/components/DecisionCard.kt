package app.whittle.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.tokens.decisionContainer
import app.whittle.design.tokens.onDecisionContainer

/** Everything a decision card shows. The payload is the exact thing that will happen. */
@Immutable
data class DecisionUi(
    val id: String,
    val icon: String,
    val title: String,
    val body: String,
    val payloadLabel: String?,
    val payload: String?,
    val approveLabel: String,
    val approveIcon: String,
    val declineLabel: String,
)

/**
 * An inline card for a choice. It never blocks: the run keeps going around it, and the answer is
 * applied whenever the user taps, even after the run has finished. Announced politely, once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DecisionCard(decision: DecisionUi, onApprove: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl))
            .padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Box(
                Modifier.size(WhittleSize.Tile.s).background(colors.decisionContainer, RoundedCornerShape(WhittleRadius.m)),
                contentAlignment = Alignment.Center,
            ) {
                WhittleIcon(decision.icon, size = WhittleSize.Icon.m, tint = colors.onDecisionContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(decision.title, style = MaterialTheme.typography.titleMedium)
                Text(decision.body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (decision.payload != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainerLowest, RoundedCornerShape(WhittleRadius.l))
                    .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
            ) {
                decision.payloadLabel?.let { label ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
                        WhittleIcon("person", size = WhittleSize.Icon.s, tint = colors.onSurfaceVariant)
                        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
                Text(decision.payload, style = MaterialTheme.typography.bodyLarge)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            Button(onClick = { haptics.perform(WhittleHaptic.Approve); onApprove() }, shapes = ButtonDefaults.shapes()) {
                WhittleIcon(decision.approveIcon, size = WhittleSize.Icon.m)
                Text(decision.approveLabel, Modifier.padding(start = WhittleSpace.s))
            }
            TextButton(onClick = { haptics.perform(WhittleHaptic.Reject); onDecline() }, shapes = ButtonDefaults.shapes()) {
                Text(decision.declineLabel)
            }
        }
    }
}

/** A question the agent asked, with the options the user can tap. */
@Immutable
data class QuestionUi(val id: String, val icon: String, val question: String, val options: List<String>)

/** Inline question card. Like a decision card it never blocks; the tapped option goes back to the agent. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QuestionCard(question: QuestionUi, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite }
            .background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl))
            .padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Box(
                Modifier.size(WhittleSize.Tile.s).background(colors.decisionContainer, RoundedCornerShape(WhittleRadius.m)),
                contentAlignment = Alignment.Center,
            ) {
                WhittleIcon(question.icon, size = WhittleSize.Icon.m, tint = colors.onDecisionContainer)
            }
            Text(question.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            question.options.forEach { option ->
                OutlinedButton(
                    onClick = { haptics.perform(WhittleHaptic.Select); onPick(option) },
                    shapes = ButtonDefaults.shapes(),
                    border = BorderStroke(WhittleSpace.hairline / 2, colors.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = colors.surfaceContainerLowest, contentColor = colors.onSurface),
                ) {
                    Text(option, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** What a decision card collapses to after a tap: one line, the outcome. */
@Composable
fun ResolvedChip(text: String, declined: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.full))
            .padding(start = WhittleSpace.s, top = WhittleSpace.xs, end = WhittleSpace.m, bottom = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        WhittleIcon(
            if (declined) "do_not_disturb_on" else "check_circle",
            fill = true,
            size = WhittleSize.Icon.m,
            tint = if (declined) colors.onSurfaceVariant else colors.primary,
        )
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    }
}
