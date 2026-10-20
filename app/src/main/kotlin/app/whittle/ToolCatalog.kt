package app.whittle

import app.whittle.connectors.FindConnectionTool
import app.whittle.agent.AgentMessages
import app.whittle.agent.ForgetTool
import app.whittle.agent.MemoryStore
import app.whittle.agent.RememberTool
import app.whittle.connectors.Connectors
import app.whittle.connectors.AddConnectionTool
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
        AddConnectionTool.DEFINITION,
        FindConnectionTool.DEFINITION,
        RememberTool.DEFINITION,
        ForgetTool.DEFINITION,
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
    ): Set<Tool> = (
        setOf(
            AskUserTool(),
            PlanTool(maxPlanItems),
            DeviceClockTool(clock),
            WebFetchTool(httpClient),
            ContactsSearchTool(context),
            CalendarEventsTool(context, clock),
            ComposeMessageTool(context),
            AddConnectionTool(context.applicationContext, connectors),
            FindConnectionTool(connectors),
            RememberTool(memory, messages),
            ForgetTool(memory, messages),
        ) + workspaceTools
        ).also { tools ->
        check(tools.map { it.definition }.toSet() == definitions.toSet()) { "ToolCatalog.definitions is out of step with create()" }
    }
}
