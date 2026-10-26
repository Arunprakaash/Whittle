package app.whittle.design.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import app.whittle.design.sprites.SpriteImage
import app.whittle.design.theme.animationsEnabled
import app.whittle.design.tokens.BeaverPose
import app.whittle.design.tokens.BeaverSprite
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** How far along the scene's ground line is: not shown, a share done, or running with no end known. */
sealed interface SceneProgress {
    data object None : SceneProgress

    data class Done(val fraction: Float) : SceneProgress

    data object Running : SceneProgress
}

/**
 * Whittle's beaver on a ground line in front of two far pixel woods, with [title] and [status] over it: the top of a
 * page that says how something stands (Linux, the link to the phone). The beaver thinks, flicking an ear now and then;
 * while something is under way ([working]) it gnaws its stick. [problem] sets the status in the error colour; a null
 * [status] is still being read and shows a skeleton line.
 */
@Composable
fun BeaverScene(
    title: String,
    status: String?,
    modifier: Modifier = Modifier,
    working: Boolean = false,
    problem: Boolean = false,
    progress: SceneProgress = SceneProgress.None,
) {
    val colors = MaterialTheme.colorScheme
    // Only a running line moves, so only then does a frame clock tick; read while drawing, it redraws without recomposing.
    val sweep = if (progress == SceneProgress.Running && animationsEnabled()) {
        rememberInfiniteTransition().animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SWEEP_MILLIS, easing = LinearEasing), RepeatMode.Restart),
        )
    } else {
        null
    }
    Box(modifier.fillMaxWidth().height(SCENE_HEIGHT)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(colors.surfaceContainer)
            // The woods are laid out on a drawing the width of a phone, kept in the middle on wider windows.
            val cell = WOODS_CELL.toPx()
            val left = (size.width - DRAWING_WIDTH.toPx()) / 2
            val ground = size.height - GROUND_HEIGHT.toPx()
            val horizon = ground - HORIZON.toPx()
            WOODS.forEach { (column, sink) ->
                PINE.forEachIndexed { row, line ->
                    line.forEachIndexed { x, pixel ->
                        if (pixel != PINE_PIXEL) return@forEachIndexed
                        // Every other column a shade apart: the dither far pixel art has.
                        val shade = if ((column + x) % 2 == 0) colors.surfaceContainerHighest else colors.outlineVariant
                        val top = horizon - (PINE.size - row - sink) * cell
                        drawRect(shade, Offset(left + (column + x) * cell, top), Size(cell, cell))
                    }
                }
            }
            drawRect(colors.surfaceContainerHigh, Offset(0f, ground), Size(size.width, size.height - ground))
            drawRect(colors.outlineVariant, Offset(0f, ground), Size(size.width, GROUND_LINE.toPx()))
            val line = PROGRESS_LINE.toPx()
            when (progress) {
                SceneProgress.None -> Unit
                is SceneProgress.Done -> drawRect(colors.onSurfaceVariant, Offset(0f, ground), Size(size.width * progress.fraction.coerceIn(0f, 1f), line))
                // A stretch that runs along the line and round again, as an indeterminate bar does; still, it sits at the start.
                SceneProgress.Running -> {
                    val length = size.width * RUNNING_SHARE
                    val start = sweep?.let { (size.width + length) * it.value - length } ?: 0f
                    val from = start.coerceAtLeast(0f)
                    drawRect(colors.onSurfaceVariant, Offset(from, ground), Size((start + length).coerceAtMost(size.width) - from, line))
                }
            }
        }
        // Decorative: the status beside it says how things stand.
        SpriteImage(
            BeaverSprite,
            if (working) BeaverPose.Working.key else BeaverPose.Thinking.key,
            contentDescription = null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = GROUND_HEIGHT).size(BEAVER_WIDTH, BEAVER_HEIGHT),
        )
        Column(Modifier.align(Alignment.TopStart).padding(WhittleSpace.l)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (status != null) {
                Text(status, style = MaterialTheme.typography.bodyMedium, color = if (problem) colors.error else colors.onSurfaceVariant)
            } else {
                SkeletonBlock(Modifier.padding(vertical = WhittleSpace.hairline).width(WhittleSize.Tile.xl * 2).height(WhittleSpace.m))
            }
        }
    }
}

private val SCENE_HEIGHT = 200.dp
private val DRAWING_WIDTH = 360.dp
private val GROUND_HEIGHT = 16.dp
private val GROUND_LINE = 2.dp
private val PROGRESS_LINE = 6.dp
/** How far above the ground line the woods stand, as far things do. */
private val HORIZON = 22.dp
private val WOODS_CELL = 3.dp

/** The sprite's 16 × 14 art pixels at 4.5 dp each. */
private val BEAVER_WIDTH = 72.dp
private val BEAVER_HEIGHT = 63.dp

/** A running line: this share of the width, sweeping across in this long. */
private const val RUNNING_SHARE = 0.35f
private const val SWEEP_MILLIS = 1400

private const val PINE_PIXEL = 'x'

/** A pine, row by row. */
private val PINE = listOf(
    "....x....",
    "...xxx...",
    "..xxxxx..",
    "...xxx...",
    "..xxxxx..",
    ".xxxxxxx.",
    "..xxxxx..",
    ".xxxxxxx.",
    "xxxxxxxxx",
    ".xxxxxxx.",
    "xxxxxxxxx",
    "....x....",
    "....x....",
)

/** Where each pine stands, in cells across the drawing, and how many cells it sinks: two woods, either side of the beaver. */
private val WOODS = listOf(10 to 3, 16 to 0, 21 to 2, 27 to 4, 32 to 1, 76 to 2, 82 to 0, 87 to 3, 93 to 1, 98 to 4)
