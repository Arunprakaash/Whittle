package app.whittle.tools.android

import app.whittle.core.common.EpochClock
import app.whittle.core.model.Provenance
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthDataToolTest {
    private val now = Instant.parse("2026-10-25T18:00:00Z")
    private val clock = EpochClock { now.toEpochMilli() }
    private val context = ToolExecutionContext(RunId("run"), ToolCallId("call"))

    /** Health Connect as a test sets it up, recording what was read. */
    private class FakeHealth(
        var availability: HealthAvailability = HealthAvailability.READY,
        var granted: Set<String> = HealthKind.permissions,
        var pastData: Boolean = true,
        var failure: Exception? = null,
    ) : HealthSource {
        val reads = mutableListOf<Triple<HealthKind, Instant, Instant>>()

        override fun availability() = availability
        override suspend fun granted() = granted
        override suspend fun pastDataAllowed() = pastData
        override suspend fun read(kind: HealthKind, from: Instant, to: Instant, byHour: Boolean, zone: ZoneId): JsonArray {
            failure?.let { throw it }
            reads += Triple(kind, from, to)
            return buildJsonArray { add(buildJsonObject { put("date", "2026-10-25"); put("steps", 4321) }) }
        }
    }

    private fun tool(health: HealthSource) = HealthDataTool(health, clock, HealthLimits(defaultDays = 7, maxDays = 366, maxHourlyDays = 2))

    private fun run(health: HealthSource, vararg arguments: Pair<String, String>) = runBlocking {
        tool(health).execute(buildJsonObject { arguments.forEach { (key, value) -> put(key, value) } }, context)
    }

    private fun body(content: String): JsonObject = Json.parseToJsonElement(content).jsonObject

    @Test
    fun `reads one kind over the last seven days by default, as trusted numbers`() {
        val health = FakeHealth()
        val output = run(health, "kind" to "steps")
        assertFalse(output.isError)
        assertEquals(Provenance.Trusted, output.provenance)
        val (kind, from, to) = health.reads.single()
        assertEquals(HealthKind.STEPS, kind)
        assertEquals(now, to)
        // Today and the six days before it, from midnight.
        val zone = ZoneId.systemDefault()
        assertEquals(now.atZone(zone).toLocalDate().minusDays(6).atStartOfDay(zone).toInstant(), from)
        val body = body(output.content)
        assertEquals("steps", body.getValue("kind").jsonPrimitive.content)
        assertEquals("day", body.getValue("by").jsonPrimitive.content)
        assertNull(body["note"])
    }

    @Test
    fun `kinds are named in words, as the model sees them`() {
        assertEquals(HealthKind.RESTING_HEART_RATE, HealthKind.of("Resting heart rate"))
        assertNull(HealthKind.of("blood sugar"))
        assertTrue(run(FakeHealth(), "kind" to "blood sugar").isError)
    }

    @Test
    fun `a kind the user didn't allow asks for Health`() {
        val output = run(FakeHealth(granted = setOf(HealthKind.STEPS.permission)), "kind" to "sleep")
        assertEquals(AndroidCapabilities.HEALTH, output.missingCapability)
    }

    @Test
    fun `without Health Connect it asks for Health, and where it can't exist it says so`() {
        assertEquals(AndroidCapabilities.HEALTH, run(FakeHealth(availability = HealthAvailability.NEEDS_APP), "kind" to "steps").missingCapability)
        val unsupported = run(FakeHealth(availability = HealthAvailability.UNSUPPORTED), "kind" to "steps")
        assertTrue(unsupported.isError)
        assertNull(unsupported.missingCapability)
    }

    @Test
    fun `ranges are checked before reading`() {
        val health = FakeHealth()
        assertTrue(run(health, "kind" to "steps", "from" to "2026-10-25", "to" to "2026-10-24").isError)
        assertTrue(run(health, "kind" to "heart rate", "by" to "hour", "from" to "2026-10-20").isError)
        assertTrue(run(health, "kind" to "steps", "from" to "2024-01-01").isError)
        assertTrue(run(health, "kind" to "steps", "by" to "week").isError)
        assertTrue(health.reads.isEmpty())
        assertFalse(run(health, "kind" to "heart rate", "by" to "hour", "from" to "2026-10-25").isError)
    }

    @Test
    fun `older data without the past-data permission comes with a note`() {
        val output = run(FakeHealth(pastData = false), "kind" to "weight", "from" to "2026-08-01")
        assertFalse(output.isError)
        assertTrue("30 days" in body(output.content).getValue("note").jsonPrimitive.content)
    }

    @Test
    fun `a refused read is an error, not a crash`() {
        val output = run(FakeHealth(failure = SecurityException("not allowed in background")), "kind" to "sleep")
        assertTrue(output.isError)
        assertTrue("not allowed in background" in output.content)
    }

    @Test
    fun `health data needs the Health capability and only reads`() {
        assertEquals(setOf(AndroidCapabilities.HEALTH), HealthDataTool.DEFINITION.requiredCapabilities)
        assertEquals(app.whittle.tools.api.RiskTier.READ, HealthDataTool.DEFINITION.riskTier)
        assertTrue(HealthDataTool.DEFINITION.exposesPersonalData)
    }
}
