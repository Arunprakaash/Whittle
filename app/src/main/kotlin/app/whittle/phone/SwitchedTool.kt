package app.whittle.phone

import app.whittle.tools.android.ToolJson
import app.whittle.tools.api.CallPreview
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.JsonObject

/**
 * A phone tool the user can switch off in Settings › On this phone. While it's off the tool still shows, so the agent
 * can tell the user why it can't and where to turn it back on, but it does nothing.
 */
class SwitchedTool(
    private val tool: Tool,
    private val switch: PhoneActionsConfig.Switch,
    private val offMessage: String,
    private val isOn: (String) -> Boolean,
) : Tool by tool {
    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput =
        if (isOn(switch.id)) tool.execute(arguments, context) else ToolJson.error(offMessage.replace(FEATURE, switch.title))

    // A switched-off tool must not read anything, not even to draw a card for a call that will do nothing.
    override suspend fun preview(arguments: JsonObject): CallPreview? = if (isOn(switch.id)) tool.preview(arguments) else null

    private companion object {
        const val FEATURE = "{{feature}}"
    }
}

/** [tools] with each one a switch names wrapped, so switching it off stops it. */
fun switched(tools: Set<Tool>, config: PhoneActionsConfig, isOn: (String) -> Boolean): Set<Tool> {
    val byTool = config.switches.flatMap { switch -> switch.tools.map { it to switch } }.toMap()
    return tools.mapTo(mutableSetOf()) { tool -> byTool[tool.definition.id.value]?.let { SwitchedTool(tool, it, config.offMessage, isOn) } ?: tool }
}
