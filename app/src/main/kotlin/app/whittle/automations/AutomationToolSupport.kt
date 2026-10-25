package app.whittle.automations

import app.whittle.agent.AgentMessages
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ToolOutput
import app.whittle.tools.api.WorkspaceId
import app.whittle.tools.workspace.ChatProjects
import java.util.Locale

/** What the tools that set up automations (schedule_task, watch_for) share: the project a run works in, and the replies. */
internal class AutomationToolSupport(
    private val automations: Automations,
    private val messages: AgentMessages,
    /** The project a chat works in, while its folder exists. */
    private val projectOf: (WorkspaceId) -> String?,
    /** Whether a project folder of that name is in the workspace. */
    private val projectExists: (String) -> Boolean,
) {
    sealed interface Project {
        /** [name] is null for the whole workspace. */
        data class Chosen(val name: String?) : Project

        data class Refused(val text: String) : Project
    }

    /** [requested] is null when the model gave none (the chat's own project), empty for the whole workspace. */
    fun project(requested: String?, workspace: WorkspaceId?): Project {
        val project = when (requested) {
            null -> workspace?.let(projectOf)
            "" -> null
            else -> requested
        }
        if (project != null) {
            if (!ChatProjects.isValidName(project)) return Project.Refused(messages.automationProjectInvalid(project))
            if (!projectExists(project)) return Project.Refused(messages.automationProjectMissing(project))
        }
        return Project.Chosen(project)
    }

    /** The tools [automation] may use without asking, as the user's own groups: "search the web, read the calendar". */
    fun toolsOf(automation: Automation): String =
        automations.config.tools.filter { automation.tools.containsAll(it.toolIds) }
            .joinToString { it.labelFor(automation.project).lowercase(Locale.ROOT) }
            .ifEmpty { messages.automationNoTools }

    fun place(project: String?): String = project?.let(messages::automationInProject) ?: messages.automationInWorkspace

    fun refusal(problem: AutomationProblem, project: String?): String = when (problem) {
        AutomationProblem.NAME_EMPTY -> messages.automationNameEmpty
        AutomationProblem.NAME_TOO_LONG -> messages.automationNameTooLong(automations.config.maxNameChars)
        AutomationProblem.PROMPT_EMPTY -> messages.automationPromptEmpty
        AutomationProblem.PROMPT_TOO_LONG -> messages.automationPromptTooLong(automations.config.maxPromptChars)
        AutomationProblem.TOO_MANY -> messages.automationTooMany(automations.config.maxAutomations)
        AutomationProblem.TOOL_NOT_ALLOWED -> messages.automationToolNotAllowed
        AutomationProblem.PROJECT_INVALID -> messages.automationProjectInvalid(project.orEmpty())
        AutomationProblem.INTERVAL_TOO_SHORT -> messages.automationIntervalTooShort(automations.config.events.minIntervalMinutes)
    }

    fun reply(text: String, error: Boolean = false) = ToolOutput(text, isError = error, provenance = Provenance.Trusted)
}
