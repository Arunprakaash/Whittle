package app.whittle.tools.android

import app.whittle.core.model.Provenance
import app.whittle.tools.api.CapabilityId
import app.whittle.tools.api.ToolOutput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Phone tools answer the model in compact JSON; user-facing words live in config, not here. */
object ToolJson {
    fun success(provenance: Provenance, body: JsonObject): ToolOutput = ToolOutput(body.toString(), isError = false, provenance = provenance)

    fun error(reason: String): ToolOutput =
        ToolOutput(buildJsonObject { put(ERROR, reason) }.toString(), isError = true, provenance = Provenance.Trusted)

    /** The harness turns this into a card asking the user, and tells the model itself. */
    fun missing(capability: CapabilityId): ToolOutput =
        ToolOutput("", isError = true, provenance = Provenance.Trusted, missingCapability = capability)

    private const val ERROR = "error"
}
