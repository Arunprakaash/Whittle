package app.whittle.design.sprites

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import app.whittle.design.theme.animationsEnabled

/**
 * Plays [pose] of [sprite]: frames, body movement and particles, redrawn every frame. Switching poses
 * cross-fades. With animations turned off in Android's settings it holds the pose's first frame, still.
 */
@Composable
fun SpriteImage(sprite: PixelSprite, pose: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    val player = remember(sprite) { SpritePlayer(sprite) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player, pose) {
        withFrameMillis { player.switchTo(pose, it); now = it }
    }
    if (animate) {
        LaunchedEffect(player) {
            while (true) withFrameMillis { now = it }
        }
    }
    val described = if (contentDescription != null) modifier.semantics { this.contentDescription = contentDescription } else modifier
    // Reading the clock only while drawing redraws each frame without recomposing.
    Canvas(described) { with(player) { draw(now, animate) } }
}

/** Keeps the poses' timing and the particles in flight; plain state, driven by the frame clock. */
internal class SpritePlayer(private val sprite: PixelSprite, private val random: Random = Random(SEED)) {
    private class Active(val name: String, val pose: SpritePose, val start: Long) {
        var lastBite = -1L
    }

    private class Particle(val x: Float, val y: Float, val vx: Float, val vy: Float, val gravity: Float, val born: Long, val life: Int, val color: Color)

    private class Look(val frame: String, val dip: Float = 0f, val scaleX: Float = 1f, val scaleY: Float = 1f) {
        val still: Boolean get() = dip == 0f && scaleX == 1f && scaleY == 1f
    }

    private var current: Active? = null
    private var previous: Active? = null
    private var fadeUntil = 0L
    private val particles = ArrayList<Particle>()

    fun switchTo(name: String, now: Long) {
        if (current?.name == name) return
        val pose = sprite.poses[name] ?: error("Sprite has no pose '$name'")
        previous = current
        fadeUntil = now + sprite.crossfadeMillis
        current = Active(name, pose, now)
        pose.particles?.takeIf { it.trigger == ParticleTrigger.Start }?.let { emit(it, now) }
    }

    fun DrawScope.draw(now: Long, animate: Boolean) {
        val active = current ?: return
        val fit = min(size.width / sprite.width, size.height / sprite.height)
        // Whole device pixels per art pixel where there's room, so a still sprite stays crisp.
        val pixel = if (fit >= 1f) floor(fit) else fit
        val origin = Offset((size.width - pixel * sprite.width) / 2, (size.height - pixel * sprite.height) / 2)
        if (!animate) {
            paint(Look(firstFrame(active.pose)), pixel, origin, 1f)
            return
        }
        emitOnBite(active, now)
        val fade = previous?.takeIf { now < fadeUntil }?.let { (fadeUntil - now).toFloat() / sprite.crossfadeMillis } ?: 0f
        if (fade > 0f) previous?.let { paint(look(it, now - it.start), pixel, origin, fade) } else previous = null
        paint(look(active, now - active.start), pixel, origin, 1f - fade)
        particles.removeAll { now - it.born >= it.life }
        particles.forEach { particle ->
            val age = (now - particle.born) / MILLIS_PER_SECOND
            val progress = (now - particle.born).toFloat() / particle.life
            val alpha = (1f - (progress - FADE_FROM) / (1f - FADE_FROM)).coerceIn(0f, 1f)
            val x = particle.x + particle.vx * age
            val y = particle.y + particle.vy * age + 0.5f * particle.gravity * age * age
            drawRect(particle.color, origin + Offset((x - 0.5f) * pixel, (y - 0.5f) * pixel), Size(pixel, pixel), alpha)
        }
    }

    private fun firstFrame(pose: SpritePose): String = pose.steps.firstOrNull()?.frame ?: pose.bite?.rest ?: error("Pose has no frames")

    /** Which frame shows [t] ms into [active], and how the body moves. */
    private fun look(active: Active, t: Long): Look {
        val pose = active.pose
        var frame = firstFrame(pose)
        var dip = 0f
        var scaleX = 1f
        var scaleY = 1f
        if (pose.steps.isNotEmpty()) {
            var at = t % pose.steps.sumOf { it.millis }
            for (step in pose.steps) {
                if (at < step.millis) {
                    frame = step.frame
                    break
                }
                at -= step.millis
            }
        }
        pose.breathe?.let { breathe ->
            val wave = sin(2 * PI * t / breathe.periodMillis).toFloat()
            scaleY += breathe.amount * wave
            scaleX -= breathe.amount * 0.5f * wave
        }
        pose.bite?.let { bite ->
            val phase = (t % bite.millis).toFloat() / bite.millis
            frame = if (phase < bite.biteAt) bite.rest else bite.bite
            val into = if (phase < bite.biteAt) 0f else (phase - bite.biteAt) / (1f - bite.biteAt)
            // Fast down, a beat held, back up: the chomp.
            val bump = sin(PI * min(1f, into * BITE_SPEED)).toFloat()
            dip = bite.dip * bump
            scaleY *= 1f - bite.squash * bump
            scaleX *= 1f + bite.squash * BITE_WIDEN * bump
        }
        pose.pop?.let { pop ->
            val progress = min(1f, t.toFloat() / pop.millis)
            val scale = pop.from + (1f - pop.from) * easeOutBack(progress, pop.overshoot)
            scaleX *= scale
            scaleY *= scale
        }
        return Look(frame, dip, scaleX, scaleY)
    }

    private fun DrawScope.paint(look: Look, pixel: Float, origin: Offset, alpha: Float) {
        val pivot = origin + Offset(sprite.pivotX * pixel, sprite.pivotY * pixel)
        // A hair of overlap hides seams between runs once the body is scaled off the pixel grid.
        val seam = if (look.still) 0f else SEAM
        withTransform({
            scale(look.scaleX, look.scaleY, pivot)
            translate(0f, look.dip * pixel)
        }) {
            sprite.runs.getValue(look.frame).forEach { run ->
                drawRect(
                    run.color,
                    origin + Offset(run.x * pixel, run.y * pixel),
                    Size(run.length * pixel + seam, pixel + seam),
                    alpha,
                )
            }
        }
    }

    private fun emitOnBite(active: Active, now: Long) {
        val bite = active.pose.bite ?: return
        val spec = active.pose.particles?.takeIf { it.trigger == ParticleTrigger.Bite } ?: return
        val t = now - active.start
        val cycle = t / bite.millis
        if ((t % bite.millis).toFloat() / bite.millis >= bite.biteAt && cycle != active.lastBite) {
            active.lastBite = cycle
            emit(spec, now)
        }
    }

    private fun emit(spec: SpriteParticles, now: Long) {
        val range = spec.angleTo - spec.angleFrom
        repeat(spec.count) { index ->
            val share = if (spec.count == 1) 0.5f else index.toFloat() / (spec.count - 1)
            val degrees = spec.angleFrom + range * share + range * JITTER * (random.nextFloat() - 0.5f)
            val radians = degrees * PI.toFloat() / HALF_TURN
            val speed = spec.speedMin + (spec.speedMax - spec.speedMin) * random.nextFloat()
            val color = sprite.palette.getValue(spec.colors[random.nextInt(spec.colors.size)])
            particles += Particle(spec.fromX, spec.fromY, cos(radians) * speed, sin(radians) * speed, spec.gravity, now, spec.lifeMillis, color)
        }
    }

    private fun easeOutBack(t: Float, overshoot: Float): Float =
        1f + (overshoot + 1f) * (t - 1f).pow(3) + overshoot * (t - 1f).pow(2)

    private companion object {
        const val SEED = 7
        const val MILLIS_PER_SECOND = 1000f
        const val HALF_TURN = 180f

        /** Particles hold full strength for this share of their life, then fade. */
        const val FADE_FROM = 0.45f

        /** How much of an angle range each particle may stray, so bursts don't look stamped. */
        const val JITTER = 0.16f

        /** The chomp comes down this much faster than the beat, then holds. */
        const val BITE_SPEED = 1.4f

        /** Squashing down widens by this share of the squash. */
        const val BITE_WIDEN = 0.7f
        const val SEAM = 0.35f
    }
}
