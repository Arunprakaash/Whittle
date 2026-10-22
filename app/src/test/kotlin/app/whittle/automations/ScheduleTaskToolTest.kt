package app.whittle.automations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ScheduleTaskToolTest {
    private fun parse(json: String) = ScheduleTaskTool.parse(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun `reads the name, prompt, time and days`() {
        val request = assertIs<ScheduleTaskTool.Parsed.Request>(
            parse("""{"name":" Brief ","prompt":"Brief me","time":"07:30","days":["mon","FRI"]}"""),
        )
        assertEquals("Brief", request.name)
        assertEquals(LocalTime.of(7, 30), request.schedule.time)
        assertEquals(setOf(1, 5), request.schedule.days)
    }

    @Test
    fun `no days, or an empty list, means every day`() {
        assertEquals(Schedule.EVERY_DAY, assertIs<ScheduleTaskTool.Parsed.Request>(parse("""{"name":"a","prompt":"b","time":"07:00"}""")).schedule.days)
        assertEquals(Schedule.EVERY_DAY, assertIs<ScheduleTaskTool.Parsed.Request>(parse("""{"name":"a","prompt":"b","time":"07:00","days":[]}""")).schedule.days)
    }

    @Test
    fun `explains a time or day it can't read`() {
        assertIs<ScheduleTaskTool.Parsed.Invalid>(parse("""{"name":"a","prompt":"b","time":"7am"}"""))
        assertIs<ScheduleTaskTool.Parsed.Invalid>(parse("""{"name":"a","prompt":"b","time":"07:00","days":["someday"]}"""))
        assertIs<ScheduleTaskTool.Parsed.Invalid>(parse("""{"name":"a","prompt":"b","time":"07:00","days":"mon"}"""))
    }
}
