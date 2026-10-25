package app.whittle.design.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

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
    val declineLabel: String,
    /** The service the action belongs to; its icon stands in for [icon] once loaded. */
    val site: String? = null,
)

/**
 * An inline card for a choice. It never blocks: the run keeps going around it, and the answer is
 * applied whenever the user taps, even after the run has finished. Announced politely, once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DecisionCard(decision: DecisionUi, onApprove: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier, declineEnabled: Boolean = true) {
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
        // A small icon beside the title, so the question leads; a service's own icon when it has one.
        Row(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            val icon: @Composable () -> Unit = { WhittleIcon(decision.icon, size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant) }
            Box(Modifier.padding(top = WhittleSpace.hairline)) {
                if (decision.site != null) SiteIcon(decision.site, containerSize = WhittleSize.Icon.m, iconSize = WhittleSize.Icon.m, fallback = icon) else icon()
            }
            Column(Modifier.weight(1f)) {
                Text(decision.title, style = MaterialTheme.typography.titleMedium)
                // Some cards need no explanation beyond their title.
                if (decision.body.isNotBlank()) Text(decision.body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
            // The verb says what happens; no icon to read past.
            Button(onClick = { haptics.perform(WhittleHaptic.Approve); onApprove() }, shapes = ButtonDefaults.shapes()) {
                Text(decision.approveLabel)
            }
            TextButton(onClick = { haptics.perform(WhittleHaptic.Reject); onDecline() }, enabled = declineEnabled, shapes = ButtonDefaults.shapes()) {
                Text(decision.declineLabel)
            }
        }
    }
}

/** A question the agent asked, with the options the user can tap. */
@Immutable
data class QuestionUi(val id: String, val icon: String, val question: String, val options: List<String>)

/**
 * Inline question card. Like a decision card it never blocks; the tapped option goes back to the agent.
 * Once answered it stays in the thread with the choice marked, so the conversation reads back clearly.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QuestionCard(question: QuestionUi, onPick: (String) -> Unit, modifier: Modifier = Modifier, answer: String? = null) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (answer == null) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl))
            .padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            WhittleIcon(question.icon, size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant, modifier = Modifier.padding(top = WhittleSpace.hairline))
            Text(question.question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            if (answer != null) {
                question.options.forEach { option -> AnsweredOption(option, chosen = option == answer) }
                return@FlowRow
            }
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

/** An option on an answered question: the choice is filled and checked, the rest step back. Not tappable. */
@Composable
private fun AnsweredOption(option: String, chosen: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .heightIn(min = WhittleSize.Button.small)
            .then(
                if (chosen) {
                    Modifier.background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.full))
                } else {
                    Modifier.border(WhittleSpace.hairline / 2, colors.outlineVariant, RoundedCornerShape(WhittleRadius.full))
                },
            )
            .semantics { selected = chosen }
            .padding(horizontal = WhittleSpace.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        if (chosen) WhittleIcon("check", size = WhittleSize.Icon.m, tint = colors.onSecondaryContainer)
        Text(
            option,
            style = MaterialTheme.typography.labelLarge,
            color = if (chosen) colors.onSecondaryContainer else colors.onSurfaceVariant.copy(alpha = UNCHOSEN_ALPHA),
        )
    }
}

private const val UNCHOSEN_ALPHA = 0.6f

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
