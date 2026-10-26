package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/**
 * One step of a setup, numbered: what to do and where, segmented with the steps beside it by [shape]. [content] is
 * what the step needs here (a field, a command to copy) under its words; [trailing], a button at the row's end. Once
 * [done], a tick takes the number's place.
 */
@Composable
fun NumberedStep(
    number: Int,
    title: String,
    detail: String,
    shape: Shape,
    modifier: Modifier = Modifier,
    done: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) = NumberedStep(number, title, AnnotatedString(detail), shape, modifier, done, trailing, content)

/** A [NumberedStep] whose detail has styled parts, such as a key in the code font. */
@Composable
fun NumberedStep(
    number: Int,
    title: String,
    detail: AnnotatedString,
    shape: Shape,
    modifier: Modifier = Modifier,
    done: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerLow).padding(WhittleSpace.m),
        // Beside its words alone the number sits in the middle; with more under them, it stays by the title.
        verticalAlignment = if (content == null) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(
            Modifier.size(WhittleSize.Tile.s).background(if (done) colors.primary else colors.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                WhittleIcon("check", size = WhittleSize.Icon.m, tint = colors.onPrimary)
            } else {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = colors.onSecondaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            content?.invoke(this)
        }
        trailing?.invoke()
    }
}
