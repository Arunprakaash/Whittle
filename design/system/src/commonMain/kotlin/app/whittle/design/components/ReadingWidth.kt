package app.whittle.design.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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

/**
 * A page's list in a [ReadingWidth]: its rows centred within [WhittleSize.readingWidthMax], at least [minInset] from
 * the sides, while the list spans [modifier]'s space so it scrolls from anywhere in it. [top] and [bottom] pad its ends.
 */
@Composable
fun ReadingLazyColumn(
    minInset: Dp,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: LazyListScope.() -> Unit,
) {
    ReadingWidth(minInset, modifier) { inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = inset, top = top, end = inset, bottom = bottom),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** A page that scrolls as one column in a [ReadingWidth], as [ReadingLazyColumn] does for a list. */
@Composable
fun ReadingScrollColumn(
    minInset: Dp,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    top: Dp = 0.dp,
    bottom: Dp = 0.dp,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    ReadingWidth(minInset, modifier) { inset ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(start = inset, top = top, end = inset, bottom = bottom),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** The side padding that keeps content [width] wide at most [WhittleSize.readingWidthMax], centred; at least [minInset]. */
fun readingInset(width: Dp, minInset: Dp): Dp = max(minInset, (width - WhittleSize.readingWidthMax) / 2)

/**
 * Keeps what it's on within [WhittleSize.readingWidthMax], centred in the space it's given and at least [minInset] from
 * its sides, so it lines up with a [ReadingWidth] of the same [minInset].
 */
fun Modifier.readingWidth(minInset: Dp = 0.dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = WhittleSize.readingWidthMax + minInset * 2).padding(horizontal = minInset)
