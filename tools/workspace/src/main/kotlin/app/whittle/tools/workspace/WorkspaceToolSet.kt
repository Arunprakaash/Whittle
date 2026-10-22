package app.whittle.tools.workspace

import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition

/** The terminal, process and file tools, built together because they share one workspace root and shell. */
object WorkspaceToolSet {
    val DEFINITIONS: List<ToolDefinition> = listOf(
        RunCommandTool.DEFINITION,
        ListFilesTool.DEFINITION,
        ReadFileTool.DEFINITION,
        WriteFileTool.DEFINITION,
        EditFileTool.DEFINITION,
        StartProcessTool.DEFINITION,
        ReadProcessOutputTool.DEFINITION,
        ListProcessesTool.DEFINITION,
        StopProcessTool.DEFINITION,
        UseProjectTool.DEFINITION,
    )

    fun create(
        workspaces: Workspaces,
        environment: ShellEnvironment,
        config: TerminalConfig,
        processes: ProcessManager,
        changes: FileChanges = FileChanges.NONE,
        projects: ChatProjects = ChatProjects.NONE,
    ): List<Tool> = listOf(
        RunCommandTool(workspaces, CommandRunner(environment, config.maxOutputChars), config),
        ListFilesTool(workspaces, config),
        ReadFileTool(workspaces, config),
        WriteFileTool(workspaces, config, changes),
        EditFileTool(workspaces, config, changes),
        StartProcessTool(workspaces, processes),
        ReadProcessOutputTool(workspaces, processes),
        ListProcessesTool(workspaces, processes),
        StopProcessTool(workspaces, processes),
        UseProjectTool(workspaces, projects),
    )
}
