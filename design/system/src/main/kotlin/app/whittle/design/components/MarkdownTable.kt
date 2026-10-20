package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSpace

/**
 * A table from model prose. Columns are as wide as their content, up to a cap that makes long cells wrap;
 * rows line up across columns. Wider than the screen, it scrolls sideways on its own.
 */
@Composable
fun MarkdownTable(header: List<String>, rows: List<List<String>>, code: SpanStyle, link: SpanStyle, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val line = colors.outlineVariant
    val shape = RoundedCornerShape(WhittleRadius.l)
    val all = listOf(header) + rows
    val columns = header.size
    Layout(
        modifier = modifier
            .clip(shape)
            .border(LINE_WIDTH, line, shape)
            .horizontalScroll(rememberScrollState()),
        content = {
            all.forEachIndexed { rowIndex, row ->
                row.forEach { cell ->
                    val isHeader = rowIndex == 0
                    Text(
                        text = lightMarkdown(cell, code, link),
                        style = if (isHeader) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight(HEADER_WEIGHT)) else MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .then(if (isHeader) Modifier.background(colors.surfaceContainerHigh) else Modifier)
                            .drawBehind {
                                // A rule under every row but the last.
                                if (rowIndex < all.lastIndex) drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), LINE_WIDTH.toPx())
                            }
                            .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val cap = MAX_COLUMN_WIDTH.roundToPx()
        // Each column takes its widest cell, capped; each row takes its tallest cell at those widths.
        val widths = IntArray(columns) { column ->
            all.indices.maxOf { row -> measurables[row * columns + column].maxIntrinsicWidth(Constraints.Infinity) }.coerceAtMost(cap)
        }
        val heights = IntArray(all.size) { row ->
            (0 until columns).maxOf { column -> measurables[row * columns + column].minIntrinsicHeight(widths[column]) }
        }
        val placeables = measurables.mapIndexed { index, measurable ->
            val row = index / columns
            val column = index % columns
            measurable.measure(Constraints.fixed(widths[column], heights[row]))
        }
        val width = widths.sum().coerceAtLeast(constraints.minWidth)
        layout(width, heights.sum()) {
            var y = 0
            for (row in all.indices) {
                var x = 0
                for (column in 0 until columns) {
                    placeables[row * columns + column].place(x, y)
                    x += widths[column]
                }
                y += heights[row]
            }
        }
    }
}

private val LINE_WIDTH = 1.dp
private val MAX_COLUMN_WIDTH = 220.dp
private const val HEADER_WEIGHT = 600
