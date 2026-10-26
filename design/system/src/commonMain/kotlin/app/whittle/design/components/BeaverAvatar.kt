package app.whittle.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.BeaverSprite
import app.whittle.design.tokens.CreationColors
import app.whittle.design.tokens.CreationTone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The beaver motifs an avatar can be drawn with. */
enum class BeaverMotif { TailScales, StumpRings, WoodChips, PixelBeaver }

/**
 * The user's avatar: a pattern from the beaver's world, drawn from [seed] so each phone keeps its own. The
 * seed picks a creation color for the ground and a motif on top: a beaver tail's crosshatch, a stump's
 * growth rings, wood chips, or a mirrored pixel pattern in the beaver sprite's own colors.
 */
@Composable
fun BeaverAvatar(seed: Long, modifier: Modifier = Modifier) {
    val tones = WhittleTheme.creationColors
    val pattern = remember(seed, tones) { AvatarPattern.from(seed, tones) }
    Canvas(modifier.clip(CircleShape)) {
        drawRect(pattern.colors.fill)
        when (pattern.motif) {
            BeaverMotif.TailScales -> tailScales(pattern)
            BeaverMotif.StumpRings -> stumpRings(pattern)
            BeaverMotif.WoodChips -> woodChips(pattern)
            BeaverMotif.PixelBeaver -> pixelBeaver(pattern)
        }
    }
}

/** Everything one seed decides, so drawing is the same at every size and on every frame. */
private class AvatarPattern(val motif: BeaverMotif, val colors: CreationColors, val seed: Long) {
    val ink: Color get() = colors.icon

    companion object {
        fun from(seed: Long, tones: Map<CreationTone, CreationColors>): AvatarPattern {
            val random = Random(seed)
            val tone = CreationTone.entries[random.nextInt(CreationTone.entries.size)]
            val motif = BeaverMotif.entries[random.nextInt(BeaverMotif.entries.size)]
            return AvatarPattern(motif, tones.getValue(tone), seed)
        }
    }
}

/** A beaver's tail: a tilted lattice of scales. */
private fun DrawScope.tailScales(pattern: AvatarPattern) {
    val random = Random(pattern.seed + TAIL_SALT)
    val step = size.minDimension / random.nextInt(TAIL_MIN_ROWS, TAIL_MAX_ROWS + 1)
    val width = step * TAIL_STROKE
    val tilt = random.nextFloat() * TAIL_MAX_TILT * 2 - TAIL_MAX_TILT
    rotate(tilt) {
        val reach = size.maxDimension
        var offset = -reach
        while (offset < reach * 2) {
            drawLine(pattern.ink.copy(alpha = TAIL_ALPHA), Offset(offset, -reach), Offset(offset + reach * 2, reach), width, StrokeCap.Round)
            drawLine(pattern.ink.copy(alpha = TAIL_ALPHA), Offset(offset, reach * 2), Offset(offset + reach * 2, 0f), width, StrokeCap.Round)
            offset += step
        }
    }
}

/** A gnawed stump seen from above: wobbly growth rings around an off-center heart. */
private fun DrawScope.stumpRings(pattern: AvatarPattern) {
    val random = Random(pattern.seed + RINGS_SALT)
    val center = Offset(size.width * (0.3f + random.nextFloat() * 0.4f), size.height * (0.3f + random.nextFloat() * 0.4f))
    val rings = random.nextInt(RINGS_MIN, RINGS_MAX + 1)
    val spacing = size.maxDimension / rings
    val wobble = spacing * RINGS_WOBBLE
    val phase = random.nextFloat() * 2 * PI.toFloat()
    for (ring in 1..rings + 1) {
        val radius = spacing * ring
        val path = Path()
        for (point in 0..RINGS_POINTS) {
            val angle = point * 2 * PI.toFloat() / RINGS_POINTS
            val r = radius + wobble * sin(angle * RINGS_LOBES + phase + ring)
            val position = Offset(center.x + r * cos(angle), center.y + r * sin(angle))
            if (point == 0) path.moveTo(position.x, position.y) else path.lineTo(position.x, position.y)
        }
        drawPath(path, pattern.ink.copy(alpha = RINGS_ALPHA), style = Stroke(width = spacing * RINGS_STROKE))
    }
}

/** Shavings left where the beaver worked: little rounded chips at every angle. */
private fun DrawScope.woodChips(pattern: AvatarPattern) {
    val random = Random(pattern.seed + CHIPS_SALT)
    val unit = size.minDimension
    repeat(random.nextInt(CHIPS_MIN, CHIPS_MAX + 1)) {
        val length = unit * (0.12f + random.nextFloat() * 0.14f)
        val thickness = length * CHIPS_THICKNESS
        val at = Offset(random.nextFloat() * size.width, random.nextFloat() * size.height)
        rotate(random.nextFloat() * FULL_TURN, pivot = at) {
            drawRoundRect(
                color = pattern.ink.copy(alpha = CHIPS_ALPHA_MIN + random.nextFloat() * CHIPS_ALPHA_RANGE),
                topLeft = Offset(at.x - length / 2, at.y - thickness / 2),
                size = Size(length, thickness),
                cornerRadius = CornerRadius(thickness / 2),
            )
        }
    }
}

/** A mirrored pixel pattern, like the beaver itself is drawn, in its fur, cheek and tooth colors. */
private fun DrawScope.pixelBeaver(pattern: AvatarPattern) {
    val random = Random(pattern.seed + PIXEL_SALT)
    val colors = PIXEL_COLORS.mapNotNull { BeaverSprite.palette[it] }
    val cell = size.minDimension / PIXEL_GRID
    val inset = (size.minDimension - cell * PIXEL_GRID) / 2
    val half = (PIXEL_GRID + 1) / 2
    for (row in 0 until PIXEL_GRID) {
        for (column in 0 until half) {
            if (random.nextFloat() > PIXEL_FILL) continue
            val color = colors[random.nextInt(colors.size)]
            for (x in setOf(column, PIXEL_GRID - 1 - column)) {
                drawRect(color, Offset(inset + x * cell, inset + row * cell), Size(cell, cell))
            }
        }
    }
}

private const val FULL_TURN = 360f

private const val TAIL_SALT = 11L
private const val TAIL_MIN_ROWS = 4
private const val TAIL_MAX_ROWS = 6
private const val TAIL_STROKE = 0.16f
private const val TAIL_MAX_TILT = 20f
private const val TAIL_ALPHA = 0.5f

private const val RINGS_SALT = 23L
private const val RINGS_MIN = 4
private const val RINGS_MAX = 6
private const val RINGS_POINTS = 48
private const val RINGS_LOBES = 3
private const val RINGS_WOBBLE = 0.12f
private const val RINGS_STROKE = 0.18f
private const val RINGS_ALPHA = 0.45f

private const val CHIPS_SALT = 37L
private const val CHIPS_MIN = 7
private const val CHIPS_MAX = 11
private const val CHIPS_THICKNESS = 0.4f
private const val CHIPS_ALPHA_MIN = 0.35f
private const val CHIPS_ALPHA_RANGE = 0.4f

private const val PIXEL_SALT = 53L
private const val PIXEL_GRID = 6
private const val PIXEL_FILL = 0.55f

/** Fur, highlight, cheek and tooth from the beaver sprite's palette. */
private val PIXEL_COLORS = listOf('B', 'H', 'C', 'T', 'D')
