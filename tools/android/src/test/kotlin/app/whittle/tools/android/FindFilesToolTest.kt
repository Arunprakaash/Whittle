package app.whittle.tools.android

import app.whittle.core.common.EpochClock
import app.whittle.core.model.RunId
import app.whittle.core.model.ToolCallId
import app.whittle.tools.api.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FindFilesToolTest {
    private val root: File = Files.createTempDirectory("find-tool").toFile().canonicalFile.also { it.deleteOnExit() }
    private val types = FileTypes(mapOf("image" to listOf("jpg")))
    private val storage = object : SharedStorage {
        override fun root() = root
    }
    private val ctx = ToolExecutionContext(RunId("r"), ToolCallId("c"))

    private fun file(path: String, content: ByteArray) {
        File(root, path).also { it.parentFile.mkdirs() }.writeBytes(content)
    }

    private fun duplicatesArgs() = buildJsonObject { put("duplicates", true) }

    private fun call(limits: FindFilesLimits, args: JsonObject = JsonObject(emptyMap()), clock: EpochClock = EpochClock { 0L }): JsonObject {
        val out = runBlocking { FindFilesTool(storage, limits, types, clock).execute(args, ctx) }
        assertTrue(!out.isError, out.content)
        return Json.parseToJsonElement(out.content).jsonObject
    }

    @Test
    fun fileListIsCappedAndCountsTheRest() {
        repeat(5) { file("f$it.bin", ByteArray(10 + it)) }

        val body = call(FindFilesLimits(maxResults = 3, scanMillis = 1_000, maxHashBytes = 1_000_000))

        assertEquals(3, body["files"]!!.jsonArray.size)
        assertEquals(5, body["matched"]!!.jsonPrimitive.int)
        assertEquals("/sdcard/f4.bin", body["files"]!!.jsonArray[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertNull(body["partial"])
    }

    @Test
    fun duplicatePathsAreCappedAcrossGroupsAndSaySo() {
        // One group of 6 copies (5 wasted * 100) and one of 3 copies (2 wasted * 50); at most 4 paths may be listed.
        repeat(6) { file("a$it.bin", ByteArray(100) { 1 }) }
        repeat(3) { file("b$it.bin", ByteArray(50) { 2 }) }

        val body = call(FindFilesLimits(maxResults = 4, scanMillis = 1_000, maxHashBytes = 1_000_000), duplicatesArgs())

        val groups = body["groups"]!!.jsonArray
        assertEquals(2, groups.size)
        assertEquals(listOf(6, 3), groups.map { it.jsonObject["copies"]!!.jsonPrimitive.int })
        assertEquals(listOf(500L, 100L), groups.map { it.jsonObject["wastedBytes"]!!.jsonPrimitive.content.toLong() })
        assertEquals(listOf(4, 0), groups.map { it.jsonObject["paths"]!!.jsonArray.size })
        assertEquals(5, body["pathsOmitted"]!!.jsonPrimitive.int)
        assertEquals(600L, body["wastedBytes"]!!.jsonPrimitive.content.toLong())
        assertEquals(2, body["matched"]!!.jsonPrimitive.int)
    }

    @Test
    fun duplicatesWithinTheCapOmitNothing() {
        repeat(2) { file("a$it.bin", ByteArray(100) { 1 }) }

        val body = call(FindFilesLimits(maxResults = 10, scanMillis = 1_000, maxHashBytes = 1_000_000), duplicatesArgs())

        assertEquals(2, body["groups"]!!.jsonArray[0].jsonObject["paths"]!!.jsonArray.size)
        assertNull(body["pathsOmitted"])
    }

    @Test
    fun partialShowsWhenTheBudgetRunsOut() {
        repeat(2) { file("a$it.bin", ByteArray(300_000) { 1 }) }

        val body = call(FindFilesLimits(maxResults = 10, scanMillis = 1_000, maxHashBytes = 1_000), duplicatesArgs())

        assertEquals(JsonPrimitive(true), body["partial"])
        assertTrue(body["groups"]!!.jsonArray.isEmpty())
    }

    @Test
    fun partialShowsWhenTheDeadlinePasses() {
        file("a.bin", ByteArray(10))
        var t = 0L
        val body = call(FindFilesLimits(maxResults = 10, scanMillis = 5, maxHashBytes = 1_000_000), clock = EpochClock { t += 10; t })

        assertEquals(JsonPrimitive(true), body["partial"])
    }
}
