package app.whittle.design.sprites

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.min

/** One step of a pose: show [frame] for [millis]. */
data class SpriteStep(val frame: String, val millis: Int)

/**
 * Pixel art generated from a sprite file (docs/design/sprites): rows of palette keys per frame, '.' empty,
 * and poses that loop through frames.
 */
class PixelSprite(
    val width: Int,
    val height: Int,
    val palette: Map<Char, Color>,
    val frames: Map<String, List<String>>,
    val poses: Map<String, List<SpriteStep>>,
) {
    /** Each frame's pixels, worked out once: runs of one colour along a row. */
    internal val runs: Map<String, List<PixelRun>> = frames.mapValues { (_, rows) ->
        rows.flatMapIndexed { y, row ->
            buildList {
                var x = 0
                while (x < row.length) {
                    var end = x + 1
                    while (end < row.length && row[end] == row[x]) end++
                    palette[row[x]]?.let { add(PixelRun(x, y, end - x, it)) }
                    x = end
                }
            }
        }
    }
}

internal class PixelRun(val x: Int, val y: Int, val length: Int, val color: Color)

/**
 * Plays [pose] of [sprite], pixel-sharp at any size. With animations turned off in Android's settings it
 * holds the pose's first frame.
 */
@Composable
fun SpriteImage(sprite: PixelSprite, pose: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val steps = sprite.poses[pose] ?: error("Sprite has no pose '$pose'")
    var index by remember(pose) { mutableStateOf(0) }
    val context = LocalContext.current
    val animate = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    if (animate && steps.size > 1) {
        LaunchedEffect(sprite, pose) {
            while (true) {
                delay(steps[index].millis.toLong())
                index = (index + 1) % steps.size
            }
        }
    }
    val runs = sprite.runs.getValue(steps[index % steps.size].frame)
    val described = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier
    Canvas(described) {
        // Whole device pixels per art pixel where there's room, so edges stay crisp.
        val fit = min(size.width / sprite.width, size.height / sprite.height)
        val pixel = if (fit >= 1f) floor(fit) else fit
        val left = (size.width - pixel * sprite.width) / 2
        val top = (size.height - pixel * sprite.height) / 2
        runs.forEach { run ->
            drawRect(run.color, Offset(left + run.x * pixel, top + run.y * pixel), Size(run.length * pixel, pixel))
        }
    }
}
