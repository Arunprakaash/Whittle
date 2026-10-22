package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/**
 * A filled text field with its label inside and no underline, as the model key is entered at first run. Under it,
 * an optional hint on the left and a counter on the right; an [error] replaces the hint. With [autoFocus] it takes
 * the keyboard as soon as it shows, the cursor after any words it starts with.
 */
@Composable
fun WhittleFilledField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    hint: String? = null,
    counter: String? = null,
    error: String? = null,
    autoFocus: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    // The field keeps its own cursor; text set from outside (a starter, a trimmed paste) puts it at the end.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val accent = when {
        error != null -> colors.error
        focused -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
        BasicTextField(
            value = field,
            onValueChange = { changed ->
                field = changed
                if (changed.text != value) onValueChange(changed.text)
            },
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            decorationBox = { input ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = WhittleSize.composerMin)
                        .clip(RoundedCornerShape(WhittleRadius.l))
                        .background(colors.surfaceContainerHigh)
                        .padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.s),
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
                    input()
                }
            },
        )
        if (error != null || hint != null || counter != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = WhittleSpace.l), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.l)) {
                Text(
                    error ?: hint.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error != null) colors.error else colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                counter?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = WhittleFonts.mono), color = colors.onSurfaceVariant) }
            }
        }
    }
}
