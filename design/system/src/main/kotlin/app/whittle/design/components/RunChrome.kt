package app.whittle.design.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleMotion
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** A finished run's steps fold into this chip ("4 steps · 15s"); tapping it unfolds them. */
@Composable
fun RunSummaryChip(text: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val chevron by animateFloatAsState(if (expanded) QUARTER_TURN else 0f, WhittleMotion.spatialDefault.spec(), label = "summaryChevron")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WhittleRadius.full))
            .background(colors.surfaceContainerLow)
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onToggle() }
            .padding(start = WhittleSpace.s, top = WhittleSpace.xs, end = WhittleSpace.m, bottom = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
    ) {
        WhittleIcon("check_circle", fill = true, size = WhittleSize.Icon.m, tint = colors.primary)
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        WhittleIcon("chevron_right", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant, modifier = Modifier.rotate(chevron))
    }
}

/** Shown when new content arrives below the fold; tapping jumps to it and resumes following. */
@Composable
fun LatestPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(WhittleRadius.full))
            .background(colors.inverseSurface)
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onClick() }
            .padding(start = WhittleSpace.s, top = WhittleSpace.xs, end = WhittleSpace.m, bottom = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
    ) {
        WhittleIcon("arrow_downward", size = WhittleSize.Icon.m, tint = colors.inverseOnSurface)
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.inverseOnSurface)
    }
}

/** Where the user types. While a run works, what they send is added to it rather than starting over. */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendLabel: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val canSend = enabled && value.isNotBlank()
    val send = { if (canSend) { haptics.perform(WhittleHaptic.Send); onSend() } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WhittleSize.composerMin)
            .background(colors.surfaceContainerHigh, RoundedCornerShape(WhittleRadius.xl))
            .padding(start = WhittleSpace.l, end = WhittleSpace.xs, top = WhittleSpace.xs, bottom = WhittleSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(vertical = WhittleSpace.s)) {
            if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = COMPOSER_MAX_LINES,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
                    // Hardware keyboards: Enter sends, Shift+Enter starts a new line.
                    .onPreviewKeyEvent { event ->
                        val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        if (!isEnter || event.isShiftPressed) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown) send()
                        true
                    },
            )
        }
        FilledIconButton(
            onClick = send,
            enabled = canSend,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
            modifier = Modifier.size(WhittleSize.touchTarget - WhittleSpace.xs).semantics { contentDescription = sendLabel },
        ) {
            WhittleIcon("arrow_upward", size = WhittleSize.Icon.xl)
        }
    }
}

private const val QUARTER_TURN = 90f
private const val COMPOSER_MAX_LINES = 6
