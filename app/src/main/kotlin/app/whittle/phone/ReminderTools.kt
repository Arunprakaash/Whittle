package app.whittle.phone

import app.whittle.core.common.Outcome
import app.whittle.core.model.Provenance
import app.whittle.reminders.ReminderAdded
import app.whittle.reminders.Reminders
import app.whittle.tools.android.ToolJson
import app.whittle.tools.android.parseTime
import app.whittle.tools.android.text
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A notification from Whittle at a time; no model runs when it shows. */
class RemindMeTool(private val reminders: Reminders) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val zone = ZoneId.systemDefault()
        val at = parseTime(arguments.text(AT), zone) ?: return ToolJson.error("at must be an ISO-8601 date-time, e.g. 2026-10-24T10:00")
        return when (val added = reminders.add(arguments.text(TEXT), at.toEpochMilli())) {
            is ReminderAdded.Invalid -> ToolJson.error(added.reason)
            is ReminderAdded.Failed -> ToolJson.error(added.error.message)
            is ReminderAdded.Added -> ToolJson.success(
                Provenance.Trusted,
                buildJsonObject {
                    put(ID, added.reminder.id)
                    put(AT, local(added.reminder.atMillis, zone))
                    put(TEXT, added.reminder.text)
                },
            )
        }
    }

    companion object {
        val DEFINITION = ToolDefinition(
            id = ToolId("remind_me"),
            description = "Shows the user a notification from Whittle at a time (ISO-8601, in their time zone unless an " +
                "offset is given). Write the text as the reminder they'll read, e.g. \"Call the bank\".",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$AT":{"type":"string","description":"When, e.g. 2026-10-24T10:00"},""" +
                    """"$TEXT":{"type":"string","description":"What to remind them of"}},"required":["$AT","$TEXT"]}""",
            ).jsonObject,
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_remind_me",
            outputProvenance = Provenance.Trusted,
        )
    }
}

/** The reminders still to come. */
class ListRemindersTool(private val reminders: Reminders) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val zone = ZoneId.systemDefault()
        val waiting = reminders.list()
        return ToolJson.success(
            Provenance.Trusted,
            buildJsonObject {
                putJsonArray(REMINDERS) {
                    waiting.forEach { reminder ->
                        addJsonObject {
                            put(ID, reminder.id)
                            put(AT, local(reminder.atMillis, zone))
                            put(TEXT, reminder.text)
                        }
                    }
                }
            },
        )
    }

    companion object {
        private const val REMINDERS = "reminders"

        val DEFINITION = ToolDefinition(
            id = ToolId("list_reminders"),
            description = "Lists the reminders Whittle will show, soonest first, with their ids.",
            inputSchema = Json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_list_reminders",
            outputProvenance = Provenance.Trusted,
        )
    }
}

/** Cancels a reminder by its id, from remind_me or list_reminders. */
class CancelReminderTool(private val reminders: Reminders) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val id = arguments.text(ID)
        if (id.isEmpty()) return ToolJson.error("id is empty")
        return when (val cancelled = reminders.cancel(id)) {
            is Outcome.Failure -> ToolJson.error(cancelled.error.message)
            is Outcome.Success ->
                if (cancelled.value) ToolJson.success(Provenance.Trusted, buildJsonObject { put(CANCELLED, true) })
                else ToolJson.error("there's no reminder $id; list_reminders gives the ids")
        }
    }

    companion object {
        private const val CANCELLED = "cancelled"

        val DEFINITION = ToolDefinition(
            id = ToolId("cancel_reminder"),
            description = "Cancels a reminder by its id, from remind_me or list_reminders.",
            inputSchema = Json.parseToJsonElement("""{"type":"object","properties":{"$ID":{"type":"string"}},"required":["$ID"]}""").jsonObject,
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_cancel_reminder",
            outputProvenance = Provenance.Trusted,
        )
    }
}

private const val ID = "id"
private const val AT = "at"
private const val TEXT = "text"

private fun local(millis: Long, zone: ZoneId): String = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).toString()
