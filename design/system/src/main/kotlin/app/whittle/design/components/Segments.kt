package app.whittle.design.components

import androidx.compose.foundation.shape.RoundedCornerShape
import app.whittle.design.tokens.WhittleRadius

/** Grouped rows: large corners on the outside of the group, small corners between rows. */
fun segmentedShape(index: Int, count: Int): RoundedCornerShape {
    val top = if (index == 0) WhittleRadius.lIncreased else WhittleRadius.s
    val bottom = if (index == count - 1) WhittleRadius.lIncreased else WhittleRadius.s
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}
