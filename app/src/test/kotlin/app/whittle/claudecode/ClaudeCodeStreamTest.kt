package app.whittle.claudecode

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClaudeCodeStreamTest {
    @Test
    fun `the session id, streamed words, tool calls and results come through`() {
        assertEquals(
            listOf(ClaudeCodeEvent.Started("s-1")),
            ClaudeCodeStream.parse("""{"type":"system","subtype":"init","session_id":"s-1","tools":["Bash"]}"""),
        )
        assertEquals(
            listOf(ClaudeCodeEvent.TextDelta("Hello")),
            ClaudeCodeStream.parse("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}},"parent_tool_use_id":null}"""),
        )
        val use = ClaudeCodeStream.parse(
            """{"type":"assistant","message":{"content":[{"type":"text","text":"I'll look."},{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]},"parent_tool_use_id":null}""",
        ).single()
        assertEquals(ClaudeCodeEvent.ToolUse("t1", "Bash", JsonObject(mapOf("command" to JsonPrimitive("ls")))), use)
        assertEquals(
            listOf(ClaudeCodeEvent.ToolResult("t1", "a\nb", isError = false)),
            ClaudeCodeStream.parse("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}]}}"""),
        )
    }

    @Test
    fun `a subagent's own steps and lines that aren't JSON are left out`() {
        assertTrue(ClaudeCodeStream.parse("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"Read","input":{}}]},"parent_tool_use_id":"t1"}""").isEmpty())
        assertTrue(ClaudeCodeStream.parse("Warning: something on stderr").isEmpty())
        assertTrue(ClaudeCodeStream.parse("").isEmpty())
    }

    @Test
    fun `a permission request is read, and answered allow or deny under its id`() {
        val request = ClaudeCodeStream.parse(
            """{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"npm install"},"tool_use_id":"t3"}}""",
        ).single()
        assertIs<ClaudeCodeEvent.PermissionRequest>(request)
        assertEquals("t3", request.toolUseId)
        val allow = Json.parseToJsonElement(ClaudeCodeStream.allow("r1", request.input)).jsonObject
        assertEquals("control_response", (allow["type"] as JsonPrimitive).content)
        val body = allow["response"]!!.jsonObject
        assertEquals("r1", (body["request_id"] as JsonPrimitive).content)
        assertEquals("allow", (body["response"]!!.jsonObject["behavior"] as JsonPrimitive).content)
        assertEquals(request.input, body["response"]!!.jsonObject["updatedInput"])
        val deny = Json.parseToJsonElement(ClaudeCodeStream.deny("r1", "No")).jsonObject["response"]!!.jsonObject["response"]!!.jsonObject
        assertEquals("deny", (deny["behavior"] as JsonPrimitive).content)
        assertIs<ClaudeCodeEvent.OtherRequest>(ClaudeCodeStream.parse("""{"type":"control_request","request_id":"r2","request":{"subtype":"hook_callback"}}""").single())
    }

    @Test
    fun `the result ends the task, with its answer or what went wrong`() {
        val done = ClaudeCodeStream.parse(
            """{"type":"result","subtype":"success","is_error":false,"result":"Fixed.","session_id":"s-1","usage":{"input_tokens":10,"output_tokens":5,"cache_read_input_tokens":2,"cache_creation_input_tokens":1}}""",
        ).single()
        assertIs<ClaudeCodeEvent.Finished>(done)
        assertEquals("Fixed.", done.text)
        assertEquals(10, done.usage.inputTokens)
        val signedOut = ClaudeCodeStream.parse("""{"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login","session_id":"s-2"}""").single()
        assertIs<ClaudeCodeEvent.Finished>(signedOut)
        assertTrue(signedOut.isError)
    }

    @Test
    fun `a failed model call says what kind of failure it was`() {
        val line = """{"type":"assistant","message":{"content":[{"type":"text","text":"Not logged in · Please run /login"}]},"parent_tool_use_id":null,"error":"authentication_failed","is_api_error_message":true}"""
        assertEquals(listOf(ClaudeCodeEvent.ApiError("authentication_failed")), ClaudeCodeStream.parse(line))
    }

    @Test
    fun `the plan's usage says which limit and when it reopens`() {
        val line = """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":1779846000,"rateLimitType":"five_hour"},"session_id":"s"}"""
        assertEquals(listOf(ClaudeCodeEvent.RateLimit("rejected", "five_hour", 1779846000)), ClaudeCodeStream.parse(line))
        assertEquals(
            listOf(ClaudeCodeEvent.RateLimit("allowed", null, null)),
            ClaudeCodeStream.parse("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"}}"""),
        )
    }

    @Test
    fun `a message to Claude Code is one line of JSON with the user's words`() {
        val line = ClaudeCodeStream.userMessage("Fix the test\nplease")
        assertTrue('\n' !in line)
        val message = Json.parseToJsonElement(line).jsonObject
        assertEquals("user", (message["type"] as JsonPrimitive).content)
    }

    @Test
    fun `the run command quotes the model and session, and resumes only with a session`() {
        val config = ClaudeCodeConfig(
            schemaVersion = 1, enabled = true, providerId = "claude-code", displayName = "Claude Code", sessionsFileName = "s.json", updatedStampFileName = "u.txt",
            installCommand = "i", removeCommand = "r", updateCommand = "u", versionCommand = "v", statusCommand = "s", signInCommand = "in", signOutCommand = "out",
            runCommand = "claude -p --model {{model}}", resumeArguments = "--resume {{session}}",
            allowedToolsArguments = "--allowedTools {{tools}}",
            preApprovedTools = mapOf("read_file" to listOf("Read"), "edit_file" to listOf("Edit", "MultiEdit")), fileArguments = emptyMap(),
            installTimeoutSeconds = 1, updateEveryHours = 1, commandTimeoutSeconds = 1, inboxPollMillis = 1, signedOutErrors = emptySet(), limitErrors = emptySet(), limitNames = emptyMap(), planNames = emptyMap(), declinedMessage = "No", outputChars = 1,
            models = listOf(ClaudeCodeModel("opus", "Opus", "")),
        )
        assertEquals("claude -p --model 'opus'", config.run("opus", null))
        assertEquals("claude -p --model 'op'\\''us' --resume 's-1'", config.run("op'us", "s-1"))
        // What an automation pre-approved runs without asking; anything else still asks.
        assertEquals(
            "claude -p --model 'opus' --allowedTools 'Read,Edit,MultiEdit'",
            config.run("opus", null, preApproved = setOf("read_file", "edit_file", "device_clock_now")),
        )
    }
}
