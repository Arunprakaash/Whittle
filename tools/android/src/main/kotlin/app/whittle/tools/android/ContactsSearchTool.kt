package app.whittle.tools.android

import android.content.Context
import android.provider.ContactsContract.CommonDataKinds.Phone
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Finds contacts by name and returns their phone numbers. Contact data can come from other people
 * (shared or synced contacts), so it is untrusted and taints the run.
 */
class ContactsSearchTool(private val context: Context) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        if (!AndroidCapabilities.isGranted(this.context, AndroidCapabilities.CONTACTS)) return ToolJson.missing(AndroidCapabilities.CONTACTS)
        val query = arguments[QUERY]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return ToolJson.error("query is empty")
        val matches = withContext(Dispatchers.IO) { search(query) }
        return ToolJson.success(
            PROVENANCE,
            buildJsonObject {
                putJsonArray(CONTACTS) {
                    matches.forEach { match ->
                        addJsonObject {
                            put(NAME, match.name)
                            put(PHONE, match.phone)
                            put(LABEL, match.label)
                        }
                    }
                }
            },
        )
    }

    private fun search(query: String): List<Match> {
        val resolver = context.contentResolver
        val projection = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL)
        val selection = "${Phone.DISPLAY_NAME} LIKE ?"
        resolver.query(Phone.CONTENT_URI, projection, selection, arrayOf("%$query%"), "${Phone.DISPLAY_NAME} ASC")?.use { cursor ->
            val name = cursor.getColumnIndexOrThrow(Phone.DISPLAY_NAME)
            val number = cursor.getColumnIndexOrThrow(Phone.NUMBER)
            val type = cursor.getColumnIndexOrThrow(Phone.TYPE)
            val label = cursor.getColumnIndexOrThrow(Phone.LABEL)
            return buildList {
                while (cursor.moveToNext() && size < MAX_MATCHES) {
                    val typeLabel = Phone.getTypeLabel(context.resources, cursor.getInt(type), cursor.getString(label)).toString()
                    add(Match(cursor.getString(name).orEmpty(), cursor.getString(number).orEmpty(), typeLabel))
                }
            }
        }
        return emptyList()
    }

    private data class Match(val name: String, val phone: String, val label: String)

    companion object {
        private const val QUERY = "query"
        private const val CONTACTS = "contacts"
        private const val NAME = "name"
        private const val PHONE = "phone"
        private const val LABEL = "label"
        /** Enough to disambiguate a name without dumping the address book into the conversation. */
        private const val MAX_MATCHES = 10
        private val PROVENANCE = Provenance.Untrusted("your contacts")

        val DEFINITION = ToolDefinition(
            id = ToolId("contacts_search"),
            description = "Finds the user's contacts whose name contains the query and returns their phone numbers. " +
                "If several match, ask the user which one with ask_user.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"$QUERY":{"type":"string","description":"Part of a contact's name"}},"required":["$QUERY"]}""",
            ).jsonObject,
            riskTier = RiskTier.READ,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = setOf(AndroidCapabilities.CONTACTS),
            consentTextKey = "consent_contacts_search",
            outputProvenance = PROVENANCE,
        )
    }
}
