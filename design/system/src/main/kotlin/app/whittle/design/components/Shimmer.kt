package app.whittle.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import app.whittle.design.tokens.WhittleMotion

/** A sweeping highlight for text that is still in progress (running step labels, the status verb). */
@Composable
fun rememberShimmerBrush(base: Color, highlight: Color): Brush {
    val position by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -SWEEP_WIDTH,
        targetValue = SWEEP_DISTANCE,
        animationSpec = infiniteRepeatable(tween(WhittleMotion.SHIMMER_LOOP_MILLIS, easing = LinearEasing)),
        label = "shimmerPosition",
    )
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(position, 0f),
        end = Offset(position + SWEEP_WIDTH, 0f),
        tileMode = TileMode.Clamp,
    )
}

private const val SWEEP_WIDTH = 240f
private const val SWEEP_DISTANCE = 900f
