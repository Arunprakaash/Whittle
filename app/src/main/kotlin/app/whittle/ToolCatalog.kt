package app.whittle

import app.whittle.agent.PlanTool
import android.content.Context
import app.whittle.agent.AskUserTool
import app.whittle.core.common.EpochClock
import app.whittle.tools.android.CalendarEventsTool
import app.whittle.tools.android.ComposeMessageTool
import app.whittle.tools.android.ContactsSearchTool
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
        CalendarEventsTool.DEFINITION,
        ComposeMessageTool.DEFINITION,
    ) + WorkspaceToolSet.DEFINITIONS

    fun create(context: Context, clock: EpochClock, httpClient: OkHttpClient, workspaceTools: List<Tool>, maxPlanItems: Int): Set<Tool> = (
        setOf(
            AskUserTool(),
            PlanTool(maxPlanItems),
            DeviceClockTool(clock),
            WebFetchTool(httpClient),
            ContactsSearchTool(context),
            CalendarEventsTool(context, clock),
            ComposeMessageTool(context),
        ) + workspaceTools
        ).also { tools ->
        check(tools.map { it.definition }.toSet() == definitions.toSet()) { "ToolCatalog.definitions is out of step with create()" }
    }
}
