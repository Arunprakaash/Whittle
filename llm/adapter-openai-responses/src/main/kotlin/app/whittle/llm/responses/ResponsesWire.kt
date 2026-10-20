package app.whittle.llm.responses

import app.whittle.llm.api.HostedTool
import kotlinx.serialization.json.Json

// Wire vocabulary of the OpenAI Responses API (POST /v1/responses with stream=true).

internal object ItemType {
    const val MESSAGE = "message"
    const val FUNCTION_CALL = "function_call"
    const val FUNCTION_CALL_OUTPUT = "function_call_output"
    const val REASONING = "reasoning"
    const val URL_CITATION = "url_citation"
    const val FUNCTION = "function"
}

/** Responses names for the tools OpenAI runs itself: the request tool type and the output item type. */
internal object HostedToolWire {
    private const val WEB_SEARCH = "web_search"
    private const val WEB_SEARCH_CALL = "web_search_call"

    fun requestType(tool: HostedTool): String = when (tool) {
        HostedTool.WEB_SEARCH -> WEB_SEARCH
    }

    fun fromItemType(itemType: String?): HostedTool? = when (itemType) {
        WEB_SEARCH_CALL -> HostedTool.WEB_SEARCH
        else -> null
    }
}

internal object ContentType {
    const val INPUT_TEXT = "input_text"
    const val INPUT_IMAGE = "input_image"
    const val OUTPUT_TEXT = "output_text"
}

internal object WireRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

internal object EventType {
    const val OUTPUT_TEXT_DELTA = "response.output_text.delta"
    const val REASONING_SUMMARY_DELTA = "response.reasoning_summary_text.delta"
    const val OUTPUT_ITEM_ADDED = "response.output_item.added"
    const val OUTPUT_ITEM_DONE = "response.output_item.done"
    const val COMPLETED = "response.completed"
    const val INCOMPLETE = "response.incomplete"
    const val FAILED = "response.failed"
    const val ERROR = "error"
}

internal object Field {
    const val TYPE = "type"
    const val ROLE = "role"
    const val CONTENT = "content"
    const val TEXT = "text"
    const val IMAGE_URL = "image_url"
    const val CALL_ID = "call_id"
    const val NAME = "name"
    const val ARGUMENTS = "arguments"
    const val OUTPUT = "output"
    const val DESCRIPTION = "description"
    const val PARAMETERS = "parameters"
    const val STRICT = "strict"
    const val MODEL = "model"
    const val INSTRUCTIONS = "instructions"
    const val INPUT = "input"
    const val TOOLS = "tools"
    const val STREAM = "stream"
    const val STORE = "store"
    const val INCLUDE = "include"
    const val DELTA = "delta"
    const val ITEM = "item"
    const val RESPONSE = "response"
    const val USAGE = "usage"
    const val INPUT_TOKENS = "input_tokens"
    const val OUTPUT_TOKENS = "output_tokens"
    const val INPUT_TOKENS_DETAILS = "input_tokens_details"
    const val CACHED_TOKENS = "cached_tokens"
    const val CACHE_WRITE_TOKENS = "cache_write_tokens"
    const val INCOMPLETE_DETAILS = "incomplete_details"
    const val REASON = "reason"
    const val ERROR = "error"
    const val MESSAGE = "message"
    const val ID = "id"
    const val ENCRYPTED_CONTENT = "encrypted_content"
    const val SUMMARY = "summary"
    const val ACTION = "action"
    const val QUERY = "query"
    const val QUERIES = "queries"
    const val URL = "url"
    const val PATTERN = "pattern"
    const val ANNOTATIONS = "annotations"
    const val TITLE = "title"
}

/** Ask for encrypted reasoning so it can be sent back on the next request without server-side storage. */
internal const val INCLUDE_ENCRYPTED_REASONING = "reasoning.encrypted_content"

internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
