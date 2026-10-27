package app.whittle

import app.whittle.connectors.FindConnectionTool
import app.whittle.agent.AgentMessages
import app.whittle.agent.ForgetTool
import app.whittle.agent.MemoryStore
import app.whittle.automations.ScheduleTaskTool
import app.whittle.agent.RememberTool
import app.whittle.connectors.Connectors
import app.whittle.connectors.AddConnectionTool
import app.whittle.agent.PlanTool
import app.whittle.apps.BuildAppTool
import app.whittle.apps.CreateAppTool
import android.content.Context
import app.whittle.agent.AskUserTool
import app.whittle.core.common.EpochClock
import app.whittle.tools.android.CalendarEventsTool
import app.whittle.tools.android.HealthDataTool
import app.whittle.tools.android.ComposeMessageTool
import app.whittle.tools.android.ContactsSearchTool
import app.whittle.tools.android.DeleteFilesTool
import app.whittle.tools.android.DeleteFilesWords
import app.whittle.tools.android.DeviceMediaScan
import app.whittle.tools.android.DeviceSharedStorage
import app.whittle.tools.android.FindFilesTool
import app.whittle.tools.android.StorageOverviewTool
import app.whittle.tools.android.AddCalendarEventTool
import app.whittle.tools.android.CallTool
import app.whittle.tools.android.OpenAppStorageTool
import app.whittle.tools.android.OpenAppTool
import app.whittle.tools.android.OpenLinkTool
import app.whittle.tools.android.OpenMapTool
import app.whittle.tools.android.SetAlarmTool
import app.whittle.tools.android.SetTimerTool
import app.whittle.phone.CancelReminderTool
import app.whittle.phone.ListRemindersTool
import app.whittle.phone.PhoneTools
import app.whittle.phone.RemindMeTool
import app.whittle.phone.ShareTool
import app.whittle.phone.switched
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.device.DeviceClockTool
import app.whittle.tools.web.WebFetchTool
import app.whittle.tools.workspace.WorkspaceToolSet
import okhttp3.OkHttpClient

/** Every tool the agent can use. Config tests read [definitions]; [create] must build the same set. */
object ToolCatalog {
    val definitions: List<ToolDefinition> = listOf(
        AskUserTool.DEFINITION,
        PlanTool.DEFINITION,
        DeviceClockTool.DEFINITION,
        WebFetchTool.DEFINITION,
        ContactsSearchTool.DEFINITION,
        StorageOverviewTool.DEFINITION,
        FindFilesTool.DEFINITION,
        DeleteFilesTool.DEFINITION,
        CalendarEventsTool.DEFINITION,
        HealthDataTool.DEFINITION,
        ComposeMessageTool.DEFINITION,
        CallTool.DEFINITION,
        SetAlarmTool.DEFINITION,
        SetTimerTool.DEFINITION,
        RemindMeTool.DEFINITION,
        ListRemindersTool.DEFINITION,
        CancelReminderTool.DEFINITION,
        AddCalendarEventTool.DEFINITION,
        OpenMapTool.DEFINITION,
        OpenAppTool.DEFINITION,
        OpenAppStorageTool.DEFINITION,
        OpenLinkTool.DEFINITION,
        ShareTool.DEFINITION,
        AddConnectionTool.DEFINITION,
        FindConnectionTool.DEFINITION,
        RememberTool.DEFINITION,
        ForgetTool.DEFINITION,
        ScheduleTaskTool.DEFINITION,
        CreateAppTool.DEFINITION,
        BuildAppTool.DEFINITION,
    ) + WorkspaceToolSet.DEFINITIONS

    fun create(
        context: Context,
        clock: EpochClock,
        httpClient: OkHttpClient,
        workspaceTools: List<Tool>,
        maxPlanItems: Int,
        connectors: Connectors,
        memory: MemoryStore,
        messages: AgentMessages,
        scheduleTask: ScheduleTaskTool,
        phone: PhoneTools,
        createApp: CreateAppTool,
        buildApp: BuildAppTool,
    ): Set<Tool> = switched(
        setOf(
            AskUserTool(),
            PlanTool(maxPlanItems),
            DeviceClockTool(clock),
            WebFetchTool(httpClient),
            ContactsSearchTool(context),
            StorageOverviewTool(context, DeviceSharedStorage(context), phone.config.storage.limits, clock),
            FindFilesTool(DeviceSharedStorage(context), phone.config.storage.findLimits, phone.config.storage.fileTypes, clock),
            DeleteFilesTool(DeviceSharedStorage(context), phone.config.storage.deleteLimits, deleteFilesWords(messages, phone.config.storage.maxDeletePerCall), DeviceMediaScan(context)),
            CalendarEventsTool(context, clock),
            HealthDataTool(phone.health, clock, phone.config.health.limits),
            ComposeMessageTool(context),
            CallTool(context),
            SetAlarmTool(context),
            SetTimerTool(context),
            RemindMeTool(phone.reminders),
            ListRemindersTool(phone.reminders),
            CancelReminderTool(phone.reminders),
            AddCalendarEventTool(context),
            OpenMapTool(context, phone.config.maps.links),
            OpenAppTool(context),
            OpenAppStorageTool(context),
            OpenLinkTool(context),
            ShareTool(context, phone.workspaces, phone.config.share),
            AddConnectionTool(context.applicationContext, connectors),
            FindConnectionTool(connectors),
            RememberTool(memory, messages),
            ForgetTool(memory, messages),
            scheduleTask,
            createApp,
            buildApp,
        ) + workspaceTools,
        phone.config,
        phone.isOn,
    ).also { tools ->
        check(tools.map { it.definition }.toSet() == definitions.toSet()) { "ToolCatalog.definitions is out of step with create()" }
    }
}

/** delete_files' words from agent-messages.json, with the per-call limit filled in. */
private fun deleteFilesWords(messages: AgentMessages, maxPaths: Int) = DeleteFilesWords(
    notDeleted = messages.deleteNotDeleted,
    gap = messages.deleteGap,
    noAccess = messages.deleteNoAccess,
    notAList = messages.deleteNotAList,
    notAPath = messages.deleteNotAPath,
    empty = messages.deleteEmpty,
    tooMany = messages.deleteTooMany(maxPaths),
    noApprovedList = messages.deleteNoApprovedList,
    controlCharacter = messages.deleteControlCharacter,
    outsideStorage = messages.deleteOutsideStorage,
    missing = messages.deleteMissing,
    unreadable = messages.deleteUnreadable,
    link = messages.deleteLink,
    folder = messages.deleteFolder,
    notRegular = messages.deleteNotRegular,
    throughLink = messages.deleteThroughLink,
    listedTwice = messages.deleteListedTwice,
    protectedFolder = messages.deleteProtectedFolder,
    notOnCard = messages.deleteNotOnCard,
    changed = messages.deleteChanged,
    refusedByAndroid = messages.deleteRefusedByAndroid,
)
