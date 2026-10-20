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

/**
 * Generates the Compose theme values from the design tokens (W3C DTCG JSON), so the app can never
 * drift from the design system. Anything the generator doesn't understand fails the build.
 */
@CacheableTask
abstract class GenerateDesignTokensTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val tokensFile: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        @Suppress("UNCHECKED_CAST")
        val tokens = JsonSlurper().parse(tokensFile.get().asFile) as Map<String, Any?>
        val generator = DesignTokenKotlin(tokens, packageName.get(), tokensFile.get().asFile.name)
        val directory = outputDirectory.get().asFile.resolve(packageName.get().replace('.', '/'))
        directory.deleteRecursively()
        directory.mkdirs()
        generator.files().forEach { (name, source) -> directory.resolve(name).writeText(source) }
    }
}

/** Turns the token tree into Kotlin source files. Pure, so it is easy to reason about. */
internal class DesignTokenKotlin(
    private val tokens: Map<String, Any?>,
    private val packageName: String,
    private val sourceName: String,
) {
    fun files(): Map<String, String> = mapOf(
        "WhittleColors.kt" to colors(),
        "WhittleTextStyles.kt" to textStyles(),
        "WhittleDimens.kt" to dimens(),
        "WhittleMotion.kt" to motion(),
        "WhittleHaptics.kt" to haptics(),
    )

    private fun colors(): String = kotlinFile(
        imports = listOf(
            "androidx.compose.material3.ColorScheme",
            "androidx.compose.material3.darkColorScheme",
            "androidx.compose.material3.lightColorScheme",
            "androidx.compose.ui.graphics.Color",
        ),
    ) {
        val modes = group("color")
        appendLine("/** Material 3 color roles for each theme. */")
        appendLine("object WhittleColors {")
        listOf("light" to "lightColorScheme", "dark" to "darkColorScheme").forEach { (mode, builder) ->
            appendLine("    val ${mode.capitalized()}: ColorScheme = $builder(")
            tokenEntries(modes.group(mode)).forEach { (role, token) -> appendLine("        $role = ${color(token.value())},") }
            appendLine("    )")
        }
        appendLine("}")
        appendLine()

        val tones = tokenEntries(group("creation")).map { it.first }
        appendLine("/** Tones for the tiles of things a user makes, picked from each creation's id. */")
        appendLine("enum class CreationTone { ${tones.joinToString { it.capitalized() }} }")
        appendLine()
        appendLine("data class CreationColors(val fill: Color, val icon: Color)")
        appendLine()
        appendLine("object WhittleCreationColors {")
        listOf("light", "dark").forEach { mode ->
            appendLine("    val ${mode.capitalized()}: Map<CreationTone, CreationColors> = mapOf(")
            tones.forEach { tone ->
                val pair = group("creation").group(tone).group(mode)
                appendLine(
                    "        CreationTone.${tone.capitalized()} to " +
                        "CreationColors(${color(pair.group("fill").value())}, ${color(pair.group("icon").value())}),",
                )
            }
            appendLine("    )")
        }
        appendLine("}")
        appendLine()

        val siteIconBackdrop = group("siteIcon").group("backdrop")
        appendLine("/** The light disc site icons sit on, per theme. */")
        appendLine("object WhittleSiteIconColors {")
        listOf("light", "dark").forEach { mode ->
            appendLine("    val ${mode.capitalized()}: Color = ${color(siteIconBackdrop.group(mode).value())}")
        }
        appendLine("}")
        appendLine()

        appendLine("// Semantic pairs: what a container means, independent of which role draws it.")
        tokenEntries(group("semantic")).forEach { (name, token) ->
            val roles = (token.value() as String).split("/")
            if (roles.size != 2) fail("semantic.$name must be 'container/onContainer'")
            appendLine("val ColorScheme.${name}Container: Color get() = ${roles[0]}")
            appendLine("val ColorScheme.on${name.capitalized()}Container: Color get() = ${roles[1]}")
        }
    }

    private fun textStyles(): String = kotlinFile(
        imports = listOf(
            "androidx.compose.ui.text.TextStyle",
            "androidx.compose.ui.text.font.FontWeight",
            "androidx.compose.ui.unit.sp",
        ),
    ) {
        appendLine("/** The type scale. Font families come from [WhittleFonts], which bundles the variable fonts. */")
        appendLine("object WhittleTextStyles {")
        tokenEntries(group("typography")).forEach { (name, token) ->
            val value = token.value().asMap("typography.$name")
            val family = (value["fontFamily"] as String).let { reference ->
                val match = REFERENCE.matchEntire(reference) ?: fail("typography.$name.fontFamily must reference a font token")
                "WhittleFonts.${match.groupValues[1].removePrefix("font.")}"
            }
            appendLine("    val $name = TextStyle(")
            appendLine("        fontFamily = $family,")
            appendLine("        fontWeight = FontWeight(${(value["fontWeight"] as Number).toInt()}),")
            appendLine("        fontSize = ${number(value["fontSize"], "px")}.sp,")
            appendLine("        lineHeight = ${number(value["lineHeight"], "px")}.sp,")
            appendLine("        letterSpacing = (${number(value["letterSpacing"], "px")}).sp,")
            appendLine("    )")
        }
        appendLine("}")
        appendLine()
        val weights = tokenEntries(group("typography"))
            .map { (name, token) -> (token.value().asMap("typography.$name")["fontWeight"] as Number).toInt() }
            .distinct()
            .sorted()
        appendLine("/** The only weights the type scale uses; [WhittleFonts] bundles exactly these instances. */")
        appendLine("val WhittleFontWeights: List<FontWeight> = listOf(${weights.joinToString { "FontWeight($it)" }})")
    }

    private fun dimens(): String = kotlinFile(imports = listOf("androidx.compose.ui.unit.dp")) {
        dimensionObject("WhittleSpace", "Spacing scale.", group("space"))
        dimensionObject("WhittleRadius", "Corner radii.", group("radius"))
        dimensionObject("WhittleSize", "Component and icon sizes.", group("size"))
        val indicator = group("agentIndicator")
        appendLine("/** The agent's state mark (the beaver) and the new-content dot. */")
        appendLine("object WhittleAgentIndicator {")
        appendLine("    val dotSize = ${number(indicator.group("dotSize").value(), "px")}.dp")
        appendLine("    val boxSize = ${number(indicator.group("boxSize").value(), "px")}.dp")
        appendLine("}")
        appendLine()
        appendLine("/** Every creation uses the same rounded square. */")
        appendLine("object WhittleCreationTile {")
        appendLine("    const val CORNER_PERCENT = ${(group("creationTile").group("cornerPercent").value() as Number).toInt()}")
        appendLine("}")
    }

    private fun StringBuilder.dimensionObject(name: String, doc: String, node: Map<String, Any?>, indent: String = "") {
        appendLine("$indent/** $doc */")
        appendLine("${indent}object $name {")
        node.filterKeys { !it.startsWith("$") }.forEach { (key, child) ->
            val childMap = child.asMap("$name.$key")
            if (childMap.containsKey("\$value")) {
                appendLine("$indent    val $key = ${number(childMap["\$value"], "px")}.dp")
            } else {
                dimensionObject(key.capitalized(), "$name.$key", childMap, "$indent    ")
            }
        }
        appendLine("$indent}")
        if (indent.isEmpty()) appendLine()
    }

    private fun motion(): String = kotlinFile(
        imports = listOf("androidx.compose.animation.core.SpringSpec", "androidx.compose.animation.core.spring"),
    ) {
        appendLine("/** A spring from the motion tokens. Spatial springs may overshoot; effects springs never do. */")
        appendLine("data class SpringToken(val dampingRatio: Float, val stiffness: Float) {")
        appendLine("    fun <T> spec(): SpringSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)")
        appendLine("}")
        appendLine()
        appendLine("object WhittleMotion {")
        val motion = group("motion")
        motion.filterKeys { !it.startsWith("$") }.forEach { (key, child) ->
            val node = child.asMap("motion.$key")
            if (node.containsKey("\$value")) {
                appendLine("    const val ${constantName(key)}_MILLIS = ${number(node["\$value"], "ms")}")
            } else {
                tokenEntries(node).forEach { (speed, token) ->
                    val spring = token.value().asMap("motion.$key.$speed")
                    appendLine(
                        "    val $key${speed.capitalized()} = SpringToken(" +
                            "dampingRatio = ${(spring["dampingRatio"] as Number).toFloat()}f, " +
                            "stiffness = ${(spring["stiffness"] as Number).toFloat()}f)",
                    )
                }
            }
        }
        appendLine("}")
    }

    private fun haptics(): String = kotlinFile(imports = listOf("androidx.compose.ui.hapticfeedback.HapticFeedbackType")) {
        val entries = tokenEntries(group("haptics")).map { (name, token) -> name to token.value().asMap("haptics.$name") }
        val envelopes = entries.mapNotNull { it.second["envelope"] as String? }.distinct()
        appendLine("/** Custom vibration shapes for moments a standard feedback type can't express. */")
        appendLine("enum class HapticEnvelope { ${envelopes.joinToString { it.capitalized() }} }")
        appendLine()
        appendLine("/** Every haptic moment in the app. Never one per streamed token. */")
        appendLine("/** [pattern] alternates on and off milliseconds; custom envelopes take their timing from it. */")
        appendLine("enum class WhittleHaptic(val feedback: HapticFeedbackType, val envelope: HapticEnvelope?, val pattern: List<Long>) {")
        entries.forEach { (name, value) ->
            val envelope = (value["envelope"] as String?)?.let { "HapticEnvelope.${it.capitalized()}" } ?: "null"
            @Suppress("UNCHECKED_CAST")
            val pattern = (value["pattern"] as? List<Number> ?: fail("haptics.$name.pattern must be a list")).joinToString { "${it.toLong()}L" }
            appendLine("    ${name.capitalized()}(HapticFeedbackType.${value["feedback"]}, $envelope, listOf($pattern)),")
        }
        appendLine("}")
    }

    // ---- helpers ----

    private fun kotlinFile(imports: List<String>, body: StringBuilder.() -> Unit): String = buildString {
        appendLine("// Generated from $sourceName by GenerateDesignTokensTask. Do not edit; change the tokens instead.")
        appendLine("package $packageName")
        appendLine()
        imports.sorted().forEach { appendLine("import $it") }
        appendLine()
        body()
    }

    private fun group(key: String): Map<String, Any?> = tokens[key].asMap(key)

    private fun Map<String, Any?>.group(key: String): Map<String, Any?> = this[key].asMap(key)

    private fun Map<String, Any?>.value(): Any? = this["\$value"] ?: fail("token has no \$value: $this")

    private fun tokenEntries(node: Map<String, Any?>): List<Pair<String, Map<String, Any?>>> =
        node.filterKeys { !it.startsWith("$") }.map { (key, value) -> key to value.asMap(key) }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(path: String): Map<String, Any?> = this as? Map<String, Any?> ?: fail("$path must be an object")

    private fun color(value: Any?): String {
        val hex = (value as? String)?.removePrefix("#")?.uppercase() ?: fail("color must be a string: $value")
        return when (hex.length) {
            6 -> "Color(0xFF$hex)"
            8 -> "Color(0x${hex.substring(6)}${hex.substring(0, 6)})"
            else -> fail("unsupported color $value")
        }
    }

    private fun number(value: Any?, unit: String): String {
        val text = (value as? String)?.removeSuffix(unit) ?: fail("expected a '$unit' value, got $value")
        val parsed = text.toBigDecimalOrNull() ?: fail("expected a number in '$value'")
        return parsed.stripTrailingZeros().toPlainString()
    }

    private fun constantName(camel: String): String = camel.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

    private fun String.capitalized(): String = replaceFirstChar { it.uppercase() }

    private fun fail(message: String): Nothing = throw GradleException("$sourceName: $message")

    private companion object {
        val REFERENCE = Regex("""\{([a-zA-Z0-9_.]+)}""")
    }
}
