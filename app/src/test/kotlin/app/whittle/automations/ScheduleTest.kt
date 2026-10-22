package app.whittle.automations

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScheduleTest {
    private val zone = ZoneId.of("Europe/London")
    private val seven = Schedule.at(LocalTime.of(7, 0), Schedule.EVERY_DAY)

    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun `due later today runs today`() {
        assertEquals(at("2026-10-22T07:00"), seven.nextAfter(at("2026-10-22T06:30"), zone))
    }

    @Test
    fun `due earlier today runs tomorrow`() {
        assertEquals(at("2026-10-23T07:00"), seven.nextAfter(at("2026-10-22T09:00"), zone))
    }

    @Test
    fun `exactly at the time means the next one`() {
        assertEquals(at("2026-10-23T07:00"), seven.nextAfter(at("2026-10-22T07:00"), zone))
    }

    @Test
    fun `weekdays skip the weekend`() {
        val weekdays = Schedule.at(LocalTime.of(7, 0), Schedule.WEEKDAYS)
        // 2026-10-23 is a Friday.
        assertEquals(at("2026-10-26T07:00"), weekdays.nextAfter(at("2026-10-23T08:00"), zone))
    }

    @Test
    fun `one day a week waits a full week once it has passed`() {
        val thursdays = Schedule.at(LocalTime.of(7, 0), setOf(4))
        // 2026-10-22 is a Thursday.
        assertEquals(at("2026-10-29T07:00"), thursdays.nextAfter(at("2026-10-22T07:01"), zone))
    }

    @Test
    fun `a time the clocks skip moves forward with them`() {
        // UK clocks go from 01:00 to 02:00 on 2027-03-28.
        val halfOne = Schedule.at(LocalTime.of(1, 30), Schedule.EVERY_DAY)
        assertEquals(at("2027-03-28T02:30"), halfOne.nextAfter(at("2027-03-28T00:00"), zone))
    }

    @Test
    fun `a schedule needs days and a time within the day`() {
        assertFailsWith<IllegalArgumentException> { Schedule(minuteOfDay = 0, days = emptySet()) }
        assertFailsWith<IllegalArgumentException> { Schedule(minuteOfDay = Schedule.MINUTES_PER_DAY, days = Schedule.EVERY_DAY) }
        assertFailsWith<IllegalArgumentException> { Schedule(minuteOfDay = 0, days = setOf(8)) }
    }
}
