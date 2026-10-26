package app.whittle.design.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.max
import app.whittle.design.tokens.WhittleSize

/**
 * A page whose content stays within [WhittleSize.readingWidthMax], centred, however wide the window: [content] gets
 * the side padding that does it, never less than [minInset]. The page itself still spans the window, so a list scrolls
 * with the wheel or trackpad anywhere in it. On a phone the screen is narrower, so the padding is [minInset] as before.
 */
@Composable
fun ReadingWidth(minInset: Dp, modifier: Modifier = Modifier, content: @Composable (inset: Dp) -> Unit) {
    BoxWithConstraints(modifier) { content(readingInset(maxWidth, minInset)) }
}

/** The side padding that keeps content [width] wide at most [WhittleSize.readingWidthMax], centred; at least [minInset]. */
fun readingInset(width: Dp, minInset: Dp): Dp = max(minInset, (width - WhittleSize.readingWidthMax) / 2)

/** Keeps what it's on within [WhittleSize.readingWidthMax], centred in the space it's given. */
fun Modifier.readingWidth(): Modifier = fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = WhittleSize.readingWidthMax)
