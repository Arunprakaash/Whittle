package app.whittle.tools.presentation

import app.whittle.core.config.ConfigName
import app.whittle.core.config.PromptTemplate
import app.whittle.core.common.getOrElse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URISyntaxException

/** Labels for one tool call, ready to show. */
@Serializable
data class StepText(val icon: String, val running: String, val done: String)

@Serializable
data class DecisionText(
    val icon: String,
    val title: String,
    val body: String,
    val payload: String?,
    val payloadLabel: String?,
    val approve: String,
    val approveIcon: String,
    val decline: String,
    val approvedOutcome: String,
    val declinedOutcome: String,
)

@Serializable
data class QuestionText(val icon: String, val question: String, val options: List<String>)

/**
 * Turns a tool call into the words the user sees. When a template can't be filled (an optional
 * argument is missing), the tool's fallback text is used instead of showing a placeholder.
 */
class ToolPresenter(private val config: ToolPresentationConfig) {
    fun step(toolId: String, arguments: JsonObject): StepText {
        val presentation = config.tools[toolId] ?: config.fallback
        val variables = variables(arguments)
        return StepText(
            icon = presentation.icon,
            running = fill(presentation.running, variables, config.fallback.running),
            done = fill(presentation.done, variables, config.fallback.done),
        )
    }

    fun decision(toolId: String, arguments: JsonObject): DecisionText {
        val decision = config.tools[toolId]?.decision ?: config.fallback.decision
            ?: error("tool-presentation.json must define fallback.decision")
        val fallback = config.fallback.decision ?: decision
        val variables = variables(arguments)
        return DecisionText(
            icon = decision.icon,
            title = fill(decision.title, variables, fallback.title),
            body = fill(decision.body, variables, fallback.body),
            payload = decision.payloadArgument?.let { variables[it] } ?: if (decision === fallback) describe(arguments) else null,
            payloadLabel = decision.payloadLabelArgument?.let { variables[it] },
            approve = fill(decision.approve, variables, fallback.approve),
            approveIcon = decision.approveIcon,
            decline = fill(decision.decline, variables, fallback.decline),
            approvedOutcome = fill(decision.approvedOutcome, variables, fallback.approvedOutcome),
            declinedOutcome = fill(decision.declinedOutcome, variables, fallback.declinedOutcome),
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

    /** The card asking for [capabilityId], or null when config has none (the tool then just reports it). */
    fun capability(capabilityId: String): CapabilityPresentation? = config.capabilities[capabilityId]

    private fun fill(template: String, variables: Map<String, String>, fallback: String): String =
        PromptTemplate(TEMPLATE_NAME, template).render(variables).getOrElse { fallback }

    /** The fallback card still shows the exact call, one argument per line. */
    private fun describe(arguments: JsonObject): String? =
        arguments.entries.joinToString("\n") { (name, value) -> "$name: ${(value as? JsonPrimitive)?.contentOrNull ?: value}" }
            .ifEmpty { null }

    private fun variables(arguments: JsonObject): Map<String, String> = buildMap {
        arguments.forEach { (name, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
            put(name, text)
            hostOf(text)?.let { put("${name}$HOST_SUFFIX", it) }
        }
    }

    private fun hostOf(text: String): String? = try {
        URI(text).takeIf { it.scheme == "https" || it.scheme == "http" }?.host?.removePrefix(WWW_PREFIX)
    } catch (exception: URISyntaxException) {
        null
    }

    companion object {
        const val HOST_SUFFIX = "_host"
        private const val WWW_PREFIX = "www."
        private val TEMPLATE_NAME = ConfigName("tool-presentation.json")
    }
}
