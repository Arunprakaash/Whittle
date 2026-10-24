package app.whittle.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em
import app.whittle.design.tokens.WhittleAgentIndicator
import app.whittle.design.tokens.WhittleFonts
import app.whittle.design.tokens.WhittleMotion
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSpace

/** What the user typed. [steerLabel] marks a message added while the agent was working. */
@Composable
fun UserMessage(text: String, modifier: Modifier = Modifier, steerLabel: String? = null) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = if (steerLabel != null) {
        colors.secondaryContainer to colors.onSecondaryContainer
    } else {
        colors.primaryContainer to colors.onPrimaryContainer
    }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .widthIn(max = maxWidth * USER_MESSAGE_WIDTH_FRACTION)
                .background(container, RoundedCornerShape(WhittleRadius.lIncreased))
                .padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
        ) {
            steerLabel?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = SECONDARY_ALPHA)) }
            Text(text, style = if (steerLabel != null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, color = content)
        }
    }
}

/** The agent's own words: narration between steps, and the answer. A breathing dot trails while it streams. */
@Composable
fun Narration(text: String, streaming: Boolean, modifier: Modifier = Modifier) {
    val codeStyle = SpanStyle(
        fontFamily = WhittleFonts.mono,
        fontSize = CODE_FONT_SCALE.em,
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    val blocks = remember(text) { MarkdownBlocks.parse(text) }
    // A blank line between paragraphs, as the text itself has.
    val paragraphGap = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    // Text runs and tables in order; the streaming dot trails the last text run, or follows a final table.
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
        blocks.forEachIndexed { index, block ->
            val last = index == blocks.lastIndex
            when (block) {
                // Each paragraph, and each run of lines in a long one, is its own text, so a long answer streaming in only
                // lays out its last few lines again. Runs stack with no gap, exactly as their lines would in one text.
                is MarkdownBlock.Text -> {
                    val paragraphs = remember(block.text) { MarkdownBlocks.paragraphs(block.text).map { MarkdownBlocks.runs(it, RUN_LINES) } }
                    val pieces = paragraphs.sumOf { it.size }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(paragraphGap)) {
                        var piece = 0
                        paragraphs.forEachIndexed { at, runs ->
                            val first = piece
                            piece += runs.size
                            key(at) {
                                Column(Modifier.fillMaxWidth()) {
                                    runs.forEachIndexed { run, lines ->
                                        key(run) {
                                            val trailing = streaming && last && at == paragraphs.lastIndex && run == runs.lastIndex
                                            NarrationText(lines, trailing, codeStyle, linkStyle, seams(first + run, pieces))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                is MarkdownBlock.Table -> {
                    MarkdownTable(block.header, block.rows, codeStyle, linkStyle)
                    if (streaming && last) NarrationText("", streaming = true, codeStyle, linkStyle)
                }
            }
        }
        if (blocks.isEmpty() && streaming) NarrationText("", streaming = true, codeStyle, linkStyle)
    }
}

/**
 * How piece [piece] of [pieces] (the paragraphs and runs a text block is split into) trims its line height, so they
 * stack exactly as the block would in one text: only its first line's top and last line's bottom are trimmed, never a
 * seam between pieces. Null for a block in one piece, which keeps the style as it is.
 */
@Composable
private fun seams(piece: Int, pieces: Int): LineHeightStyle? {
    if (pieces == 1) return null
    val base = MaterialTheme.typography.bodyLarge.lineHeightStyle ?: LineHeightStyle.Default
    val trim = when (piece) {
        0 -> if (base.trim == LineHeightStyle.Trim.Both || base.trim == LineHeightStyle.Trim.FirstLineTop) LineHeightStyle.Trim.FirstLineTop else LineHeightStyle.Trim.None
        pieces - 1 -> if (base.trim == LineHeightStyle.Trim.Both || base.trim == LineHeightStyle.Trim.LastLineBottom) LineHeightStyle.Trim.LastLineBottom else LineHeightStyle.Trim.None
        else -> LineHeightStyle.Trim.None
    }
    return LineHeightStyle(base.alignment, trim)
}

@Composable
private fun NarrationText(text: String, streaming: Boolean, codeStyle: SpanStyle, linkStyle: SpanStyle, lineHeightStyle: LineHeightStyle? = null) {
    val formatted = remember(text, codeStyle, linkStyle) { lightMarkdown(text, codeStyle, linkStyle) }
    val annotated = buildAnnotatedString {
        append(formatted)
        if (streaming) appendInlineContent(STREAMING_DOT_ID, STREAMING_DOT_ALTERNATE_TEXT)
    }
    val inline = if (streaming) {
        mapOf(
            STREAMING_DOT_ID to InlineTextContent(Placeholder(DOT_PLACEHOLDER.em, DOT_PLACEHOLDER.em, PlaceholderVerticalAlign.TextCenter)) {
                StreamingDot()
            },
        )
    } else {
        emptyMap()
    }
    val style = MaterialTheme.typography.bodyLarge.let { if (lineHeightStyle != null) it.copy(lineHeightStyle = lineHeightStyle) else it }
    Text(annotated, Modifier.fillMaxWidth(), style = style, inlineContent = inline)
}

@Composable
private fun StreamingDot() {
    val scale by rememberInfiniteTransition(label = "streamingDot").animateFloat(
        initialValue = 1f,
        targetValue = DOT_MIN_SCALE,
        animationSpec = infiniteRepeatable(tween(WhittleMotion.AGENT_BREATHE_MILLIS / 2), RepeatMode.Reverse),
        label = "streamingDotScale",
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(WhittleAgentIndicator.dotSize)
                .graphicsLayer { scaleX = scale; scaleY = scale; alpha = scale }
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

private const val STREAMING_DOT_ID = "streamingDot"

/** Lines per text in a long paragraph: as text streams in, only the last run of this many lines is laid out again. */
private const val RUN_LINES = 10

/** Read by accessibility services in place of the dot; Compose requires it to be non-empty. */
private const val STREAMING_DOT_ALTERNATE_TEXT = "…"
private const val DOT_PLACEHOLDER = 0.9f

/** Monospace runs a little large next to Figtree at the same size. */
private const val CODE_FONT_SCALE = 0.9f
private const val DOT_MIN_SCALE = 0.55f
private const val SECONDARY_ALPHA = 0.7f
/** User messages leave a gutter on the left so the thread reads as a conversation. */
private const val USER_MESSAGE_WIDTH_FRACTION = 0.85f
