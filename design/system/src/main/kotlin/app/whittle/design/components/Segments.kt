package app.whittle.design.components

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import app.whittle.design.tokens.WhittleRadius

/** Grouped rows: large corners on the outside of the group, small corners between rows. */
fun segmentedShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) WhittleRadius.lIncreased else WhittleRadius.s
    val bottom = if (index == count - 1) WhittleRadius.lIncreased else WhittleRadius.s
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Connected buttons in a row: fully round on the group's outer ends, small corners where buttons meet. */
fun connectedShape(index: Int, count: Int): RoundedCornerShape {
    val outer = CornerSize(FULL_PERCENT)
    val inner = CornerSize(WhittleRadius.s)
    val start = if (index == 0) outer else inner
    val end = if (index == count - 1) outer else inner
    return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
}

private const val FULL_PERCENT = 50
