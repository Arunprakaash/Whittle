package app.whittle.llm.anthropic

import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Message
import app.whittle.core.model.Role
import app.whittle.llm.api.HostedTool
import app.whittle.llm.api.ModelRequest
import app.whittle.llm.api.ProviderDescriptor
import app.whittle.llm.api.ReasoningEffort
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import com.fasterxml.jackson.core.JsonProcessingException

/**
 * Turns the canonical conversation into a Messages request. Caching marks the system prompt and lets the API cache the
 * conversation as it grows; reasoning models think adaptively, at the run's effort, with a readable summary.
 */
internal class AnthropicRequestMapper {
    fun map(request: ModelRequest, provider: ProviderDescriptor): MessageCreateParams {
        val model = request.model
        val caching = model.capabilities.promptCaching
        val builder = MessageCreateParams.builder()
            .model(model.id.value)
            .maxTokens(request.maxOutputTokens.toLong())
            .messages(request.messages.mapNotNull(::message))
        if (request.systemPrompt.isNotEmpty()) {
            builder.systemOfTextBlockParams(
                listOf(TextBlockParam.builder().text(request.systemPrompt).apply { if (caching) cacheControl(EPHEMERAL) }.build()),
            )
        }
        // Top-level caching moves its breakpoint to the end of the conversation on each request, so every step reads the last.
        if (caching) builder.cacheControl(EPHEMERAL)
        if (model.capabilities.toolCalling) {
            request.tools.forEach { tool ->
                builder.addTool(
                    Tool.builder()
                        .name(tool.name)
                        .description(tool.description)
                        .inputSchema(jsonMapper().readValue(tool.inputSchema.toString(), Tool.InputSchema::class.java))
                        .build(),
                )
            }
            if (HostedTool.WEB_SEARCH in request.hostedTools.intersect(provider.hostedToolsFor(model.id))) {
                builder.addTool(WebSearchTool20260209.builder().build())
            }
        }
        if (model.capabilities.reasoning) {
            builder.thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
            builder.outputConfig(OutputConfig.builder().effort(effort(request.reasoningEffort)).build())
        }
        // The SDK sends its own API version; anything else the provider config adds (a beta) goes as is.
        provider.defaultHeaders.filterKeys { !it.equals(VERSION_HEADER, ignoreCase = true) }.forEach { (name, value) -> builder.putAdditionalHeader(name, value) }
        provider.requestFieldsFor(model.id).forEach { (name, value) ->
            builder.putAdditionalBodyProperty(name, JsonValue.fromJsonNode(jsonMapper().readTree(value.toString())))
        }
        return builder.build()
    }

    /** Adaptive thinking can't be turned off on current models, so the least effort stands in for none. */
    private fun effort(effort: ReasoningEffort): OutputConfig.Effort = when (effort) {
        ReasoningEffort.NONE, ReasoningEffort.LOW -> OutputConfig.Effort.LOW
        ReasoningEffort.MEDIUM -> OutputConfig.Effort.MEDIUM
        ReasoningEffort.HIGH -> OutputConfig.Effort.HIGH
    }

    private fun message(message: Message): MessageParam? = when (message.role) {
        Role.USER -> userMessage(message)
        Role.ASSISTANT -> assistantMessage(message)
    }

    /** Tool results first, as the API requires, then the text and images the user added. */
    private fun userMessage(message: Message): MessageParam? {
        val results = message.content.filterIsInstance<ContentBlock.ToolResult>().map { result ->
            ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder().toolUseId(result.toolCallId.value).content(result.content).isError(result.isError).build(),
            )
        }
        val parts = message.content.mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> block.text.takeIf { it.isNotEmpty() }?.let { ContentBlockParam.ofText(TextBlockParam.builder().text(it).build()) }
                is ContentBlock.Image -> ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.of(block.mediaType)).data(block.base64Data).build())
                        .build(),
                )
                // The runner turns an attached image file into an Image before the request reaches an adapter.
                is ContentBlock.ImageFile, is ContentBlock.ToolCall, is ContentBlock.ToolResult, is ContentBlock.Reasoning, is ContentBlock.HostedToolUse -> null
            }
        }
        val blocks = results + parts
        return blocks.takeIf { it.isNotEmpty() }?.let { MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(it).build() }
    }

    /**
     * A Claude turn goes back exactly as it came (see [AnthropicTurnCodec]). A turn from another model, or one cut short
     * before Claude finished it, is rebuilt from its text and tool calls.
     */
    private fun assistantMessage(message: Message): MessageParam? {
        message.content.firstNotNullOfOrNull { (it as? ContentBlock.Reasoning)?.providerState?.let(AnthropicTurnCodec::unwrap) }?.let { content ->
            return AnthropicTurnCodec.toParam(content)
        }
        val blocks = message.content.mapNotNull { block ->
            when (block) {
                is ContentBlock.Text -> block.text.takeIf { it.isNotEmpty() }?.let { ContentBlockParam.ofText(TextBlockParam.builder().text(it).build()) }
                is ContentBlock.ToolCall -> ContentBlockParam.ofToolUse(
                    ToolUseBlockParam.builder().id(block.id.value).name(block.toolName).input(input(block.argumentsJson)).build(),
                )
                is ContentBlock.Reasoning, is ContentBlock.HostedToolUse, is ContentBlock.Image, is ContentBlock.ImageFile, is ContentBlock.ToolResult -> null
            }
        }
        return blocks.takeIf { it.isNotEmpty() }?.let { MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(it).build() }
    }

    /** A tool call's arguments as the API's object; arguments that don't parse go as an empty object. */
    private fun input(argumentsJson: String): ToolUseBlockParam.Input = try {
        jsonMapper().readValue(argumentsJson.ifBlank { EMPTY_OBJECT }, ToolUseBlockParam.Input::class.java)
    } catch (exception: JsonProcessingException) {
        jsonMapper().readValue(EMPTY_OBJECT, ToolUseBlockParam.Input::class.java)
    }

    private companion object {
        val EPHEMERAL: CacheControlEphemeral = CacheControlEphemeral.builder().build()
        const val VERSION_HEADER = "anthropic-version"
        const val EMPTY_OBJECT = "{}"
    }
}
