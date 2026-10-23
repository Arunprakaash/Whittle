package app.whittle.tools.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.whittle.core.model.Provenance
import app.whittle.tools.api.ExecutionSite
import app.whittle.tools.api.RiskTier
import app.whittle.tools.api.Tool
import app.whittle.tools.api.ToolDefinition
import app.whittle.tools.api.ToolExecutionContext
import app.whittle.tools.api.ToolId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Opens the user's messaging app with the recipient and text filled in. It never sends: the user taps
 * send there, so Whittle needs no SMS permission. A config rule makes it always show a decision card.
 */
class ComposeMessageTool(private val context: Context) : Tool {
    override val definition = DEFINITION

    override suspend fun execute(arguments: JsonObject, context: ToolExecutionContext): ToolOutput {
        val phone = arguments.text(PHONE_NUMBER)
        val text = arguments.text(TEXT)
        if (phone.isEmpty() || !PHONE_PATTERN.matches(phone)) return ToolJson.error("phoneNumber is not a phone number")
        if (text.isEmpty()) return ToolJson.error("text is empty")
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(SMS_SCHEME + Uri.encode(phone))).putExtra(SMS_BODY_EXTRA, text)
        if (!this.context.startHandOff(intent)) return ToolJson.error("no messaging app is installed")
        return ToolJson.success(Provenance.Trusted, buildJsonObject { put(OPENED, true); put(SENT, false) })
    }

    companion object {
        private const val RECIPIENT_NAME = "recipientName"
        private const val PHONE_NUMBER = "phoneNumber"
        private const val TEXT = "text"
        private const val OPENED = "openedInMessages"
        private const val SENT = "sent"
        private const val SMS_SCHEME = "smsto:"
        private const val SMS_BODY_EXTRA = "sms_body"
        private val PHONE_PATTERN = Regex("""^\+?[0-9 ()\-.]{3,}$""")

        val DEFINITION = ToolDefinition(
            id = ToolId("compose_message"),
            description = "Opens the user's messaging app with a text message ready to send to a phone number. " +
                "Nothing is sent until the user taps send there. Look the number up with contacts_search first.",
            inputSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{""" +
                    """"$RECIPIENT_NAME":{"type":"string","description":"Who it's for, as the user knows them"},""" +
                    """"$PHONE_NUMBER":{"type":"string","description":"Phone number"},""" +
                    """"$TEXT":{"type":"string","description":"The message"}""" +
                    """},"required":["$RECIPIENT_NAME","$PHONE_NUMBER","$TEXT"]}""",
            ).jsonObject,
            // A draft handed to another app on this phone; the user does the sending.
            riskTier = RiskTier.WRITE_LOCAL,
            executionSite = ExecutionSite.DEVICE,
            requiredCapabilities = emptySet(),
            consentTextKey = "consent_compose_message",
            outputProvenance = Provenance.Trusted,
        )
    }
}
