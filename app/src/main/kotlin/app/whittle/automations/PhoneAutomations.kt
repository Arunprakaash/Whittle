package app.whittle.automations

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.whittle.AppGraph
import app.whittle.automations.ui.AutomationAction
import app.whittle.automations.ui.AutomationEntry
import app.whittle.automations.ui.AutomationIdea as IdeaEntry
import app.whittle.automations.ui.AutomationIssue
import app.whittle.automations.ui.AutomationSave
import app.whittle.automations.ui.AutomationsHost
import app.whittle.automations.ui.AutomationsPlatform
import app.whittle.automations.ui.AutomationsState
import app.whittle.automations.ui.RunNow
import app.whittle.automations.ui.ToolChoice
import app.whittle.background.rememberNotificationPermissionRequest
import app.whittle.phone.rememberCapabilityRequest
import app.whittle.tools.api.CapabilityId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The phone's automations as the Automations pages show them, each with its next run worked out here, and their
 * changes saved: for the phone and the user's computers alike. [projects] are the workspace's project folders.
 */
class PhoneAutomationsHost(
    private val graph: AppGraph,
    private val runner: AutomationRunner,
    projects: StateFlow<List<String>>,
) : AutomationsHost {
    private val automations = graph.automations
    private val config = automations.config
    private val tools = config.tools.map { ToolChoice(it.key, it.icon, it.label, it.labelInProject, it.toolIds, it.capabilities) }

    override val state: StateFlow<AutomationsState> = combine(automations.all, projects) { all, folders -> stateOf(all, folders) }
        .stateIn(graph.appScope, SharingStarted.Eagerly, stateOf(automations.all.value, projects.value))

    // Writes go to the app's scope so leaving the screen never drops a change.
    override fun act(action: AutomationAction) {
        graph.appScope.launch {
            when (action) {
                is AutomationAction.SetEnabled -> automations.setEnabled(action.id, action.on)
                is AutomationAction.Delete -> automations.delete(action.id)
            }
        }
    }

    override suspend fun save(automation: AutomationEntry): AutomationSave = when (val result = automations.save(automation.toAutomation())) {
        is SaveResult.Saved -> AutomationSave.Saved(entryOf(result.automation))
        is SaveResult.Refused -> AutomationSave.Refused(ISSUES.getValue(result.problem))
        is SaveResult.Failed -> AutomationSave.Failed(result.error.message)
    }

    override suspend fun runNow(id: String): RunNow = when (runner.runNow(id)) {
        RunNowResult.STARTED -> RunNow.Started
        RunNowResult.BUSY -> RunNow.Busy
        RunNowResult.NO_MODEL -> RunNow.NoModel
        RunNowResult.NOT_FOUND -> RunNow.NotFound
    }

    private fun stateOf(all: List<Automation>, folders: List<String>) = AutomationsState(
        automations = all.map(::entryOf),
        // A new automation, and each idea, are fresh drafts with their own ids each time the list changes.
        blank = entryOf(automations.draft()),
        ideas = config.ideas.map { IdeaEntry(it.icon, it.name, entryOf(automations.draft(it))) },
        tools = tools,
        projects = folders,
        maxAutomations = config.maxAutomations,
        maxNameChars = config.maxNameChars,
        maxPromptChars = config.maxPromptChars,
        zone = automations.zone.id,
    )

    private fun entryOf(automation: Automation) = AutomationEntry(
        id = automation.id,
        name = automation.name,
        prompt = automation.prompt,
        minuteOfDay = automation.schedule.minuteOfDay,
        days = automation.schedule.days,
        tools = automation.tools,
        project = automation.project,
        enabled = automation.enabled,
        lastRunAtMillis = automation.lastRunAtMillis,
        lastChatId = automation.lastChatId,
        missedAtMillis = automation.missedAtMillis,
        nextRunMillis = automations.nextRunMillis(automation),
    )

    // The run's own facts (last run, missed) are the store's; it keeps the stored ones whatever is sent.
    private fun AutomationEntry.toAutomation() = Automation(
        id = id,
        name = name,
        prompt = prompt,
        schedule = Schedule(minuteOfDay, days),
        tools = tools,
        project = project,
        enabled = enabled,
    )

    private companion object {
        val ISSUES = mapOf(
            AutomationProblem.NAME_EMPTY to AutomationIssue.NameEmpty,
            AutomationProblem.NAME_TOO_LONG to AutomationIssue.NameTooLong,
            AutomationProblem.PROMPT_EMPTY to AutomationIssue.PromptEmpty,
            AutomationProblem.PROMPT_TOO_LONG to AutomationIssue.PromptTooLong,
            AutomationProblem.TOO_MANY to AutomationIssue.TooMany,
            AutomationProblem.TOOL_NOT_ALLOWED to AutomationIssue.ToolNotAllowed,
            AutomationProblem.PROJECT_INVALID to AutomationIssue.ProjectInvalid,
        )
    }
}

/** What Automations do through the phone itself: Android's permission and notification prompts, and toasts. */
@Composable
fun rememberPhoneAutomationsPlatform(graph: AppGraph): AutomationsPlatform {
    val context = LocalContext.current
    // The answer goes to whichever row asked last.
    var answer by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    val capabilityRequest = rememberCapabilityRequest(graph.healthConnect) { granted -> answer(granted) }
    val requestNotifications = rememberNotificationPermissionRequest()
    return remember(context, capabilityRequest, requestNotifications) {
        AutomationsPlatform(
            requestCapabilities = { capabilities, onDone -> answer = onDone; capabilityRequest(capabilities.map(::CapabilityId)) },
            requestNotifications = requestNotifications,
            toast = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
        )
    }
}
