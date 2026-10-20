import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** Generates the Compose sprite (frames, palette, poses) from a pixel sprite file, so the app draws exactly what was designed. */
@CacheableTask
abstract class GenerateSpriteKotlinTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spriteFile: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val sprite = PixelSpriteSource.read(spriteFile.get().asFile)
        val directory = outputDirectory.get().asFile.resolve(packageName.get().replace('.', '/'))
        directory.deleteRecursively()
        directory.mkdirs()
        directory.resolve("${sprite.name}Sprite.kt").writeText(SpriteKotlin(sprite, packageName.get(), spriteFile.get().asFile.name).source())
    }
}

/** Generates the launcher, themed and notification drawables from a pixel sprite file's "icons" section. */
@CacheableTask
abstract class GenerateSpriteDrawablesTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spriteFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val sprite = PixelSpriteSource.read(spriteFile.get().asFile)
        val drawables = outputDirectory.get().asFile.resolve("drawable")
        outputDirectory.get().asFile.deleteRecursively()
        drawables.mkdirs()
        sprite.icons.forEach { (key, icon) ->
            drawables.resolve("${resourceName(key)}.xml").writeText(SpriteVector(sprite, icon, spriteFile.get().asFile.name).xml())
        }
    }

    /** launcherForeground → ic_launcher_foreground. */
    private fun resourceName(key: String): String =
        "ic_" + key.replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]}_${it.groupValues[2]}" }.lowercase()
}

/** [monochrome] icons are one shape that Android tints (themed icon, notification); [holes] are keys left out of them. */
internal class SpriteIcon(val frame: String, val viewport: Int, val pixel: Double, val monochrome: Boolean, val holes: Set<Char>)

/** A pixel sprite file, read and checked: every frame the right size, every pixel a palette key, every pose a real frame. */
internal class PixelSpriteSource(
    val name: String,
    val width: Int,
    val height: Int,
    val pivot: Pair<Double, Double>,
    val crossfadeMillis: Int,
    val palette: Map<Char, String>,
    val frames: Map<String, List<String>>,
    val poses: Map<String, PoseSource>,
    val icons: Map<String, SpriteIcon>,
) {
    companion object {
        private val HEX_COLOR = Regex("#[0-9A-Fa-f]{6}")
        const val EMPTY = '.'

        @Suppress("UNCHECKED_CAST")
        fun read(file: File): PixelSpriteSource {
            val json = JsonSlurper().parse(file) as Map<String, Any?>
            fun fail(message: String): Nothing = throw GradleException("${file.name}: $message")
            fun Map<String, Any?>.entries(): Map<String, Any?> = filterKeys { !it.startsWith("$") }
            val name = json["name"] as? String ?: fail("name is required")
            val width = (json["width"] as? Number)?.toInt() ?: fail("width is required")
            val height = (json["height"] as? Number)?.toInt() ?: fail("height is required")
            val palette = (json["palette"] as? Map<String, Any?> ?: fail("palette is required")).entries().map { (key, value) ->
                if (key.length != 1 || key[0] == EMPTY) fail("palette key '$key' must be one character other than '$EMPTY'")
                if (value !is String || !HEX_COLOR.matches(value)) fail("palette '$key' must be #RRGGBB")
                key[0] to value
            }.toMap()
            val frames = (json["frames"] as? Map<String, Any?> ?: fail("frames is required")).entries().mapValues { (frame, rows) ->
                val lines = (rows as? List<Any?>)?.map { it as? String ?: fail("frame $frame rows must be strings") } ?: fail("frame $frame must be a list")
                if (lines.size != height) fail("frame $frame has ${lines.size} rows, not $height")
                lines.forEachIndexed { y, row ->
                    if (row.length != width) fail("frame $frame row $y is ${row.length} wide, not $width")
                    row.firstOrNull { it != EMPTY && it !in palette }?.let { fail("frame $frame row $y uses '$it', which isn't in the palette") }
                }
                lines
            }
            fun frameName(pose: String, value: Any?): String {
                val frame = value as? String ?: fail("pose $pose names a frame that isn't a string")
                if (frame !in frames) fail("pose $pose uses unknown frame $frame")
                return frame
            }
            fun Map<String, Any?>.number(pose: String, key: String): Double =
                (this[key] as? Number)?.toDouble() ?: fail("pose $pose needs a number for $key")
            fun Map<String, Any?>.positive(pose: String, key: String): Int =
                number(pose, key).toInt().takeIf { it > 0 } ?: fail("pose $pose $key must be positive")
            fun Map<String, Any?>.pair(pose: String, key: String): Pair<Double, Double> {
                val list = this[key] as? List<Any?> ?: fail("pose $pose $key must be [a, b]")
                val numbers = list.map { (it as? Number)?.toDouble() ?: fail("pose $pose $key must be numbers") }
                if (numbers.size != 2) fail("pose $pose $key must have two numbers")
                return numbers[0] to numbers[1]
            }
            val poses = (json["poses"] as? Map<String, Any?> ?: fail("poses is required")).entries().mapValues { (pose, value) ->
                val spec = value as? Map<String, Any?> ?: fail("pose $pose must be an object")
                val steps = (spec["steps"] as? List<Map<String, Any?>>).orEmpty().map { step ->
                    frameName(pose, step["frame"]) to step.positive(pose, "millis")
                }
                val breathe = (spec["breathe"] as? Map<String, Any?>)?.let { it.number(pose, "amount") to it.positive(pose, "periodMillis") }
                val bite = (spec["bite"] as? Map<String, Any?>)?.let {
                    val biteAt = it.number(pose, "biteAt")
                    if (biteAt <= 0 || biteAt >= 1) fail("pose $pose biteAt must be between 0 and 1")
                    BiteSource(it.positive(pose, "millis"), frameName(pose, it["rest"]), frameName(pose, it["bite"]), biteAt, it.number(pose, "dip"), it.number(pose, "squash"))
                }
                if (steps.isEmpty() && bite == null) fail("pose $pose needs steps or a bite")
                val pop = (spec["pop"] as? Map<String, Any?>)?.let { Triple(it.number(pose, "from"), it.number(pose, "overshoot"), it.positive(pose, "millis")) }
                val particles = (spec["particles"] as? Map<String, Any?>)?.let {
                    val trigger = when (it["on"]) {
                        "bite" -> if (bite == null) fail("pose $pose has particles on bite but no bite") else "Bite"
                        "start" -> "Start"
                        else -> fail("pose $pose particles need \"on\": \"bite\" or \"start\"")
                    }
                    val colors = (it["keys"] as? List<Any?>)?.map { key -> (key as? String)?.singleOrNull()?.takeIf { c -> c in palette } ?: fail("pose $pose particle keys must be palette keys") }
                        ?.takeIf { keys -> keys.isNotEmpty() } ?: fail("pose $pose particles need keys")
                    ParticlesSource(trigger, colors, it.pair(pose, "from"), it.positive(pose, "count"), it.positive(pose, "lifeMillis"), it.pair(pose, "angles"), it.pair(pose, "speed"), it.number(pose, "gravity"))
                }
                PoseSource(steps, breathe, bite, pop, particles)
            }
            val pivot = (json["pivot"] as? List<Any?>)?.map { (it as? Number)?.toDouble() ?: fail("pivot must be numbers") }?.takeIf { it.size == 2 } ?: fail("pivot must be [x, y]")
            val crossfadeMillis = (json["crossfadeMillis"] as? Number)?.toInt()?.takeIf { it >= 0 } ?: fail("crossfadeMillis is required")
            val icons = (json["icons"] as? Map<String, Any?>)?.entries().orEmpty().mapValues { (key, value) ->
                val icon = value as? Map<String, Any?> ?: fail("icon $key must be an object")
                val frame = icon["frame"] as? String ?: fail("icon $key needs a frame")
                if (frame !in frames) fail("icon $key uses unknown frame $frame")
                val viewport = (icon["viewport"] as? Number)?.toInt() ?: fail("icon $key needs a viewport")
                val pixel = (icon["pixel"] as? Number)?.toDouble() ?: fail("icon $key needs a pixel size")
                if (pixel * width > viewport || pixel * height > viewport) fail("icon $key doesn't fit its viewport")
                val holes = (icon["holes"] as? List<Any?>).orEmpty().map { (it as? String)?.singleOrNull() ?: fail("icon $key holes must be palette keys") }
                holes.firstOrNull { it !in palette }?.let { fail("icon $key hole '$it' isn't in the palette") }
                val monochrome = icon["monochrome"] as? Boolean ?: false
                if (holes.isNotEmpty() && !monochrome) fail("icon $key has holes but isn't monochrome")
                SpriteIcon(frame, viewport, pixel, monochrome, holes.toSet())
            }
            return PixelSpriteSource(name, width, height, pivot[0] to pivot[1], crossfadeMillis, palette, frames, poses, icons)
        }
    }
}

internal class BiteSource(val millis: Int, val rest: String, val bite: String, val biteAt: Double, val dip: Double, val squash: Double)

internal class ParticlesSource(
    val trigger: String,
    val colors: List<Char>,
    val from: Pair<Double, Double>,
    val count: Int,
    val lifeMillis: Int,
    val angles: Pair<Double, Double>,
    val speed: Pair<Double, Double>,
    val gravity: Double,
)

internal class PoseSource(
    val steps: List<Pair<String, Int>>,
    val breathe: Pair<Double, Int>?,
    val bite: BiteSource?,
    val pop: Triple<Double, Double, Int>?,
    val particles: ParticlesSource?,
)

/** The sprite as Kotlin: a [PixelSprite][app.whittle.design.sprites.PixelSprite] value and an enum of its poses. */
internal class SpriteKotlin(private val sprite: PixelSpriteSource, private val packageName: String, private val sourceName: String) {
    fun source(): String = buildString {
        val name = sprite.name
        appendLine("// Generated from $sourceName by GenerateSpriteKotlinTask. Do not edit; change the sprite file instead.")
        appendLine("package $packageName")
        appendLine()
        appendLine("import androidx.compose.ui.graphics.Color")
        listOf("ParticleTrigger", "PixelSprite", "SpriteBite", "SpriteBreathe", "SpriteParticles", "SpritePop", "SpritePose", "SpriteStep")
            .forEach { appendLine("import app.whittle.design.sprites.$it") }
        appendLine()
        appendLine("/** The poses $name can play. */")
        appendLine("enum class ${name}Pose(val key: String) {")
        sprite.poses.keys.forEach { appendLine("    ${it.replaceFirstChar(Char::uppercase)}(\"$it\"),") }
        appendLine("}")
        appendLine()
        appendLine("val ${name}Sprite: PixelSprite = PixelSprite(")
        appendLine("    width = ${sprite.width},")
        appendLine("    height = ${sprite.height},")
        appendLine("    pivotX = ${f(sprite.pivot.first)},")
        appendLine("    pivotY = ${f(sprite.pivot.second)},")
        appendLine("    crossfadeMillis = ${sprite.crossfadeMillis},")
        appendLine("    palette = mapOf(")
        sprite.palette.forEach { (key, hex) -> appendLine("        '$key' to Color(0xFF${hex.drop(1).uppercase()}),") }
        appendLine("    ),")
        appendLine("    frames = mapOf(")
        sprite.frames.forEach { (frame, rows) ->
            appendLine("        \"$frame\" to listOf(")
            rows.forEach { appendLine("            \"$it\",") }
            appendLine("        ),")
        }
        appendLine("    ),")
        appendLine("    poses = mapOf(")
        sprite.poses.forEach { (pose, spec) ->
            appendLine("        \"$pose\" to SpritePose(")
            appendLine("            steps = listOf(${spec.steps.joinToString { (frame, millis) -> "SpriteStep(\"$frame\", $millis)" }}),")
            spec.breathe?.let { (amount, period) -> appendLine("            breathe = SpriteBreathe(${f(amount)}, $period),") }
            spec.bite?.let {
                appendLine("            bite = SpriteBite(${it.millis}, \"${it.rest}\", \"${it.bite}\", ${f(it.biteAt)}, ${f(it.dip)}, ${f(it.squash)}),")
            }
            spec.pop?.let { (from, overshoot, millis) -> appendLine("            pop = SpritePop(${f(from)}, ${f(overshoot)}, $millis),") }
            spec.particles?.let {
                appendLine("            particles = SpriteParticles(")
                appendLine("                trigger = ParticleTrigger.${it.trigger},")
                appendLine("                colors = listOf(${it.colors.joinToString { c -> "'$c'" }}),")
                appendLine("                fromX = ${f(it.from.first)},")
                appendLine("                fromY = ${f(it.from.second)},")
                appendLine("                count = ${it.count},")
                appendLine("                lifeMillis = ${it.lifeMillis},")
                appendLine("                angleFrom = ${f(it.angles.first)},")
                appendLine("                angleTo = ${f(it.angles.second)},")
                appendLine("                speedMin = ${f(it.speed.first)},")
                appendLine("                speedMax = ${f(it.speed.second)},")
                appendLine("                gravity = ${f(it.gravity)},")
                appendLine("            ),")
            }
            appendLine("        ),")
        }
        appendLine("    ),")
        appendLine(")")
    }

    /** A Kotlin Float literal. */
    private fun f(value: Double): String = "${value}f"
}

/** One icon as an Android vector drawable: a path per colour, each row's runs merged into rectangles. */
internal class SpriteVector(private val sprite: PixelSpriteSource, private val icon: SpriteIcon, private val sourceName: String) {
    fun xml(): String = buildString {
        val rows = sprite.frames.getValue(icon.frame)
        val left = (icon.viewport - sprite.width * icon.pixel) / 2
        val top = (icon.viewport - sprite.height * icon.pixel) / 2
        val runs = linkedMapOf<String, StringBuilder>()
        rows.forEachIndexed { y, row ->
            var x = 0
            while (x < row.length) {
                val key = row[x]
                var end = x + 1
                while (end < row.length && row[end] == key) end++
                val drawn = key != PixelSpriteSource.EMPTY && key !in icon.holes
                if (drawn) {
                    val color = if (icon.monochrome) MONOCHROME else "#FF" + sprite.palette.getValue(key).drop(1).uppercase()
                    val length = (end - x) * icon.pixel
                    runs.getOrPut(color) { StringBuilder() }
                        .append("M${n(left + x * icon.pixel)},${n(top + y * icon.pixel)}h${n(length)}v${n(icon.pixel)}h${n(-length)}z")
                }
                x = end
            }
        }
        appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        appendLine("<!-- Generated from $sourceName (frame \"${icon.frame}\") by GenerateSpriteDrawablesTask. Do not edit. -->")
        appendLine("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"")
        appendLine("    android:width=\"${icon.viewport}dp\"")
        appendLine("    android:height=\"${icon.viewport}dp\"")
        appendLine("    android:viewportWidth=\"${icon.viewport}\"")
        appendLine("    android:viewportHeight=\"${icon.viewport}\">")
        runs.forEach { (color, path) ->
            appendLine("    <path")
            appendLine("        android:fillColor=\"$color\"")
            appendLine("        android:pathData=\"$path\" />")
        }
        appendLine("</vector>")
    }

    private fun n(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0')

    private companion object {
        /** Single-colour icons (themed, notification) are tinted by Android; only their shape counts. */
        const val MONOCHROME = "#FFFFFFFF"
    }
}
