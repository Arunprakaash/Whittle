package app.whittle.tools.workspace

import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition

/** The terminal and file tools, built together because they share one workspace root and shell. */
object WorkspaceToolSet {
    val DEFINITIONS: List<ToolDefinition> = listOf(
        RunCommandTool.DEFINITION,
        ListFilesTool.DEFINITION,
        ReadFileTool.DEFINITION,
        WriteFileTool.DEFINITION,
        EditFileTool.DEFINITION,
    )

    fun create(workspaces: Workspaces, environment: ShellEnvironment, config: TerminalConfig): List<Tool> = listOf(
        RunCommandTool(workspaces, CommandRunner(environment, config.maxOutputChars), config),
        ListFilesTool(workspaces, config),
        ReadFileTool(workspaces, config),
        WriteFileTool(workspaces, config),
        EditFileTool(workspaces, config),
    )
}
