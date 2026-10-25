package app.whittle.tools.presentation

import kotlinx.serialization.json.booleanOrNull
import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import app.whittle.core.common.getOrElse
import app.whittle.tools.api.ToolOrigin
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URISyntaxException

/** Labels for one tool call, ready to show. [site] is the host whose icon stands in for the tool's, if any. */
@Serializable
data class StepText(val icon: String, val running: String, val done: String, val site: String? = null)

@Serializable
data class DecisionText(
    val icon: String,
    val title: String,
    val body: String,
    val payload: String?,
    val payloadLabel: String?,
    val approve: String,
    val decline: String,
    val approvedOutcome: String,
    val declinedOutcome: String,
    /** The host whose icon stands in for [icon]: the service a connected tool belongs to. */
    val site: String? = null,
)

@Serializable
data class QuestionText(val icon: String, val question: String, val options: List<String>)

/**
 * Turns a tool call into the words the user sees. When a template can't be filled (an optional
 * argument is missing), the tool's fallback text is used instead of showing a placeholder. A tool from
 * elsewhere ([ToolOrigin]) also fills {{toolTitle}} and {{toolSource}} with its provider's own names.
 * Every argument also fills `<argument>_when`: in plain words when it's a time ([PlainTime]), as written otherwise.
 */
class ToolPresenter(private val config: ToolPresentationConfig, clock: UserClock = UserClock()) {
    private val plainTime = config.times?.let { PlainTime(it, clock) }

    /** Every argument name that holds the address of a page a step reads. */
    val siteArguments: Set<String> = config.tools.values.mapNotNullTo(mutableSetOf()) { it.siteArgument }

    fun step(toolId: String, arguments: JsonObject, origin: ToolOrigin? = null): StepText {
        val presentation = presentation(toolId) ?: config.fallback
        val variables = variables(arguments, origin)
        return StepText(
            icon = presentation.icon,
            running = fill(presentation.running, variables, config.fallback.running),
            done = fill(presentation.done, variables, config.fallback.done),
            site = presentation.siteArgument?.let { variables["$it$HOST_SUFFIX"] } ?: origin?.site,
        )
    }

    fun decision(toolId: String, arguments: JsonObject, origin: ToolOrigin? = null): DecisionText {
        val decision = presentation(toolId)?.decision ?: config.fallback.decision
            ?: error("tool-presentation.json must define fallback.decision")
        val fallback = config.fallback.decision ?: decision
        val variables = variables(arguments, origin)
        return DecisionText(
            icon = decision.icon,
            title = fill(decision.title, variables, fallback.title),
            body = (listOf(fill(decision.body, variables, fallback.body)) + flagNotes(decision, arguments)).filter { it.isNotBlank() }.joinToString(SENTENCE_GAP),
            payload = decision.payloadArgument?.let { variables[it] } ?: if (decision === fallback) describe(arguments) else null,
            payloadLabel = decision.payloadLabelArgument?.let { variables[it] },
            approve = fill(decision.approve, variables, fallback.approve),
            decline = fill(decision.decline, variables, fallback.decline),
            approvedOutcome = fill(decision.approvedOutcome, variables, fallback.approvedOutcome),
            declinedOutcome = fill(decision.declinedOutcome, variables, fallback.declinedOutcome),
            site = origin?.site,
        )
    }

    /** The question and its options, or null when the call doesn't carry a usable question. */
    fun question(toolId: String, arguments: JsonObject): QuestionText? {
        val presentation = config.tools[toolId]?.question ?: return null
        val question = (arguments[presentation.questionArgument] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val options = (arguments[presentation.optionsArgument] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
        return if (question.isEmpty() || options.isEmpty()) null else QuestionText(presentation.icon, question, options)
    }

    private fun flagNotes(decision: DecisionPresentation, arguments: JsonObject): List<String> =
        decision.flagNotes.filterKeys { (arguments[it] as? JsonPrimitive)?.booleanOrNull == true }.values.toList()

    /** The card asking for [capabilityId], or null when config has none (the tool then just reports it). */
    private fun presentation(toolId: String): ToolPresentation? =
        config.tools[toolId] ?: config.toolPrefixes.entries.firstOrNull { toolId.startsWith(it.key) }?.value

    fun capability(capabilityId: String): CapabilityPresentation? = config.capabilities[capabilityId]

    private fun fill(template: String, variables: Map<String, String>, fallback: String): String =
        PromptTemplate(TEMPLATE_NAME, template).render(variables).getOrElse { fallback }

    /** The fallback card still shows the exact call, one argument per line. */
    private fun describe(arguments: JsonObject): String? =
        arguments.entries.joinToString("\n") { (name, value) -> "$name: ${(value as? JsonPrimitive)?.contentOrNull ?: value}" }
            .ifEmpty { null }

    private fun variables(arguments: JsonObject, origin: ToolOrigin?): Map<String, String> = buildMap {
        arguments.forEach { (name, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
            put(name, text)
            hostOf(text)?.let { put("${name}$HOST_SUFFIX", it) }
            put("${name}$WHEN_SUFFIX", plainTime?.format(text) ?: text)
        }
        // Set last, so an argument of the same name can't pass itself off as the tool's own name.
        origin?.let {
            put(TOOL_TITLE, it.title)
            put(TOOL_SOURCE, it.source)
        }
    }

    private fun hostOf(text: String): String? = try {
        URI(text).takeIf { it.scheme == "https" || it.scheme == "http" }?.host?.removePrefix(WWW_PREFIX)
    } catch (exception: URISyntaxException) {
        null
    }

    companion object {
        const val HOST_SUFFIX = "_host"
        const val WHEN_SUFFIX = "_when"
        const val TOOL_TITLE = "toolTitle"
        const val TOOL_SOURCE = "toolSource"
        private const val SENTENCE_GAP = " "
        private const val WWW_PREFIX = "www."
        private val TEMPLATE_NAME = ConfigName("tool-presentation.json")
    }
}
