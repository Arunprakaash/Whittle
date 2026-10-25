package app.whittle.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.whittle.design.components.WhittleIcon
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/**
 * Text the user copies to use elsewhere (a return address, a command), with a copy button; [label] above it and [hint]
 * below, when given. [code] sets it in the mono face, for commands.
 */
@Composable
internal fun CopyableText(text: String, copyDescription: String, onCopy: () -> Unit, label: String? = null, hint: String? = null, code: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceContainerLowest, RoundedCornerShape(WhittleRadius.l)).padding(start = WhittleSpace.m, top = WhittleSpace.s, bottom = WhittleSpace.s),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
    ) {
        label?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = if (code) MaterialTheme.typography.bodyMedium.copy(fontFamily = WhittleFonts.mono) else MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCopy) {
                WhittleIcon("content_copy", size = WhittleSize.Icon.l, contentDescription = copyDescription)
            }
        }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(end = WhittleSpace.m)) }
    }
}
