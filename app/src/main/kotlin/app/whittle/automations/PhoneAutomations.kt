package app.whittle.automations

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.whittle.AppGraph
import app.whittle.automations.ui.AutomationAction
import app.whittle.automations.ui.AutomationEntry
import app.whittle.automations.ui.AutomationIdea as IdeaEntry
import app.whittle.automations.ui.AutomationIssue
import app.whittle.automations.ui.AutomationSave
import app.whittle.automations.ui.AutomationsHost
import app.whittle.automations.ui.AutomationsPlatform
import app.whittle.automations.ui.AppChoice
import app.whittle.automations.ui.AutomationsState
import app.whittle.automations.ui.PhoneReadings
import app.whittle.automations.ui.EventLimits
import app.whittle.automations.ui.RunNow
import app.whittle.automations.ui.ToolChoice
import app.whittle.automations.events.AndroidPhoneReader
import app.whittle.automations.events.EventSources
import app.whittle.automations.events.capabilityAllowed
import app.whittle.background.rememberNotificationPermissionRequest
import app.whittle.phone.rememberCapabilityRequest
import app.whittle.tools.api.CapabilityId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's automations as the Automations pages show them, each with its next run worked out here, and their
 * changes saved: for the phone and the user's computers alike. [projects] are the workspace's project folders.
 */
class PhoneAutomationsHost(
    private val graph: AppGraph,
    private val runner: AutomationRunner,
    projects: StateFlow<List<String>>,
    private val sources: EventSources,
    /** Whether the phone granted a capability, for what a trigger still needs. */
    private val granted: (String) -> Boolean,
) : AutomationsHost {
    private val automations = graph.automations
    private val config = automations.config
    private val events = config.events
    private val gate = graph.eventGate
    private val tools = config.tools.map { ToolChoice(it.key, it.icon, it.label, it.labelInProject, it.toolIds, it.capabilities, it.writes) }
    private val limits = EventLimits(
        minIntervalMinutes = events.minIntervalMinutes,
        defaultEveryMinutes = events.defaultEveryMinutes,
        defaultPlaceRadiusMeters = events.defaultPlaceRadiusMeters,
        minPlaceRadiusMeters = events.minPlaceRadiusMeters,
        maxPlaceNameChars = events.maxPlaceNameChars,
        maxNumberDigits = events.maxNumberDigits,
        placeCheckMinutes = events.placeCheckMinutes,
        placePolling = events.placePolling,
        capabilities = events.capabilities,
    )

    // What's granted and switched on is read again with each recheck (a prompt answered, the app resumed).
    private val allowance = combine(graph.currentSettings, sources.checks) { settings, _ -> settings?.phoneOff.orEmpty() }

    override val state: StateFlow<AutomationsState> =
        combine(automations.all, projects, gate.dropped, gate.saveError, allowance) { all, folders, dropped, saveError, off ->
            stateOf(all, folders, dropped, saveError != null, off)
        }.stateIn(graph.appScope, SharingStarted.Eagerly, stateOf(automations.all.value, projects.value, emptyMap(), false, emptySet()))

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
        is SaveResult.Saved -> {
            switchOnFor(result.automation.trigger)
            AutomationSave.Saved(entryOf(result.automation, state.value.automations.associate { it.id to it.skippedToday }, phoneOff()))
        }
        is SaveResult.Refused -> AutomationSave.Refused(ISSUES.getValue(result.problem))
        is SaveResult.Failed -> AutomationSave.Failed(result.error.message)
    }

    override suspend fun runNow(id: String): RunNow = when (runner.runNow(id)) {
        RunNowResult.STARTED -> RunNow.Started
        RunNowResult.BUSY -> RunNow.Busy
        RunNowResult.NO_MODEL -> RunNow.NoModel
        RunNowResult.NOT_FOUND -> RunNow.NotFound
    }

    /**
     * Saving an automation on an event is asking for it: the switches in Settings › On this phone that cover what it
     * needs go on, as switching one on there would. What Android grants is asked for next, by the editor.
     */
    private suspend fun switchOnFor(trigger: Trigger?) {
        val needed = trigger?.capabilities(events.capabilities).orEmpty()
        graph.phoneActions.switches.filter { switch -> switch.capabilities.any { it in needed } }.forEach { graph.settings.setPhoneFeature(it.id, true) }
    }

    private fun phoneOff(): Set<String> = graph.currentSettings.value?.phoneOff.orEmpty()

    private fun stateOf(all: List<Automation>, folders: List<String>, dropped: Map<String, Int>, notSaved: Boolean, off: Set<String>) = AutomationsState(
        automations = all.map { entryOf(it, dropped, off) },
        // A new automation, and each idea, are fresh drafts with their own ids each time the list changes.
        blank = entryOf(automations.draft()),
        ideas = config.ideas.map { IdeaEntry(it.icon, it.name, entryOf(automations.draft(it))) },
        tools = tools,
        projects = folders,
        maxAutomations = config.maxAutomations,
        maxNameChars = config.maxNameChars,
        maxPromptChars = config.maxPromptChars,
        zone = automations.zone.id,
        events = limits,
        eventsNotSaved = notSaved,
    )

    /** [dropped] counts today's skipped events by automation; [off] are the switches turned off in Settings. */
    private fun entryOf(automation: Automation, dropped: Map<String, Int> = emptyMap(), off: Set<String> = emptySet()) = AutomationEntry(
        id = automation.id,
        name = automation.name,
        prompt = automation.prompt,
        // A trigger's automation keeps the editor's starting time, for when the user switches it to one.
        minuteOfDay = automation.schedule?.minuteOfDay ?: config.defaultMinuteOfDay,
        days = automation.schedule?.days ?: Schedule.EVERY_DAY,
        tools = automation.tools,
        project = automation.project,
        enabled = automation.enabled,
        lastRunAtMillis = automation.lastRunAtMillis,
        lastChatId = automation.lastChatId,
        missedAtMillis = automation.missedAtMillis,
        nextRunMillis = automations.nextRunMillis(automation),
        trigger = automation.trigger,
        skippedToday = dropped[automation.id] ?: 0,
        missing = automation.trigger?.let { trigger -> missingFor(trigger, off) }.orEmpty(),
    )

    /** What [trigger] needs that isn't granted, or whose switch is off. */
    private fun missingFor(trigger: Trigger, off: Set<String>): List<String> =
        trigger.capabilities(events.capabilities).filterNot { capabilityAllowed(it, graph.phoneActions.switches, off, granted) }

    // The run's own facts (last run, missed) are the store's; it keeps the stored ones whatever is sent.
    private fun AutomationEntry.toAutomation() = Automation(
        id = id,
        name = name,
        prompt = prompt,
        schedule = if (trigger == null) Schedule(minuteOfDay, days) else null,
        trigger = trigger,
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
            AutomationProblem.INTERVAL_TOO_SHORT to AutomationIssue.IntervalTooShort,
            AutomationProblem.SENDER_REQUIRED to AutomationIssue.SenderRequired,
        )
    }
}

/**
 * What Automations do through the phone itself: Android's permission and notification prompts, toasts, and what the
 * trigger editor reads (the apps, where the phone is, its Wi-Fi). What's granted is read again after each prompt and
 * whenever the user comes back to the app, so the event sources and the list follow it.
 */
@Composable
fun rememberPhoneAutomationsPlatform(graph: AppGraph, sources: EventSources): AutomationsPlatform {
    val context = LocalContext.current
    // The answer goes to whichever row asked last.
    var answer by remember { mutableStateOf<(Boolean) -> Unit>({}) }
    val capabilityRequest = rememberCapabilityRequest(graph.healthConnect) { granted -> sources.recheck(); answer(granted) }
    val requestNotifications = rememberNotificationPermissionRequest()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { sources.recheck() }
    val readings = remember(context) {
        val reader = AndroidPhoneReader(context, graph.automations.config.events)
        PhoneReadings(
            apps = { withContext(Dispatchers.Default) { launchableApps(context).map { AppChoice(it.packageName, it.label) } } },
            location = reader::location,
            wifi = reader::wifi,
        )
    }
    return remember(context, capabilityRequest, requestNotifications) {
        AutomationsPlatform(
            requestCapabilities = { capabilities, onDone -> answer = onDone; capabilityRequest(capabilities.map(::CapabilityId)) },
            requestNotifications = requestNotifications,
            toast = { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() },
            phone = readings,
        )
    }
}

/** The apps with a launcher icon, by name, for a notification trigger and watch_for. At target 28 Android hides no packages. */
fun launchableApps(context: Context): List<LaunchableApp> {
    val packages = context.packageManager
    return packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { LaunchableApp(it.activityInfo.packageName, it.loadLabel(packages).toString()) }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
