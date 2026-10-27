package app.whittle.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import app.whittle.design.components.WhittleIcon
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize

/** One button of [ChoiceButtons]: its label, and an icon before it when there's one. */
internal data class ButtonChoice(val label: String, val icon: String? = null)

/**
 * A few choices as one connected button group (M3 Expressive), the [selected] one checked; for the choices of a
 * SettingsChoiceRow. Picking one applies it at once.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ChoiceButtons(choices: List<ButtonChoice>, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    val haptics = WhittleTheme.haptics
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        choices.forEachIndexed { index, choice ->
            val checked = index == selected
            ToggleButton(
                checked = checked,
                onCheckedChange = { haptics.perform(WhittleHaptic.Select); onSelect(index) },
                enabled = enabled,
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    choices.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                choice.icon?.let {
                    WhittleIcon(it, fill = checked, size = WhittleSize.Icon.m)
                    Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
                }
                Text(choice.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
