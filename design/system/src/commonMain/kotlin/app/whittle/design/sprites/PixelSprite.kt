package app.whittle.design.sprites

import androidx.compose.ui.graphics.Color

/** One step of a pose: show [frame] for [millis]. */
data class SpriteStep(val frame: String, val millis: Int)

/** The body swells and settles: scales up by [amount] at the top of each [periodMillis]. */
data class SpriteBreathe(val amount: Float, val periodMillis: Int)

/**
 * A chewing loop: [rest] until [biteAt] (0–1) of each [millis], then [bite], dipping [dip] art pixels and
 * squashing by [squash] as the teeth come down.
 */
data class SpriteBite(val millis: Int, val rest: String, val bite: String, val biteAt: Float, val dip: Float, val squash: Float)

/** The pose springs in from [from] of its size, overshooting by [overshoot] (ease-out-back), over [millis]. */
data class SpritePop(val from: Float, val overshoot: Float, val millis: Int)

enum class ParticleTrigger { Bite, Start }

/**
 * Bits that fly off: [count] at each bite or once at the start, from [fromX],[fromY] (art pixels), spread
 * evenly over [angleFrom]..[angleTo] degrees (−90 is up) at [speedMin]..[speedMax] art pixels a second,
 * falling with [gravity], fading out over [lifeMillis].
 */
data class SpriteParticles(
    val trigger: ParticleTrigger,
    val colors: List<Char>,
    val fromX: Float,
    val fromY: Float,
    val count: Int,
    val lifeMillis: Int,
    val angleFrom: Float,
    val angleTo: Float,
    val speedMin: Float,
    val speedMax: Float,
    val gravity: Float,
)

/** How a pose plays: frames in [steps] or a [bite] loop, with optional movement and particles. */
data class SpritePose(
    val steps: List<SpriteStep> = emptyList(),
    val breathe: SpriteBreathe? = null,
    val bite: SpriteBite? = null,
    val pop: SpritePop? = null,
    val particles: SpriteParticles? = null,
)

/**
 * Pixel art generated from a sprite file (docs/design/sprites): rows of palette keys per frame ('.' empty),
 * and poses that move it around [pivotX],[pivotY]. Switching poses cross-fades over [crossfadeMillis].
 */
class PixelSprite(
    val width: Int,
    val height: Int,
    val pivotX: Float,
    val pivotY: Float,
    val crossfadeMillis: Int,
    val palette: Map<Char, Color>,
    val frames: Map<String, List<String>>,
    val poses: Map<String, SpritePose>,
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
