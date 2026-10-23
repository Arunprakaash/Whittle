package app.whittle.tools.android

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class AlarmTimesTest {
    private val justAfterMidnight = LocalDateTime.parse("2026-10-24T00:08")

    @Test
    fun `a one-off alarm rings at the next such time, today when it's still ahead`() {
        assertEquals(LocalDateTime.parse("2026-10-24T06:30"), AlarmTimes.nextRing(justAfterMidnight, LocalTime.parse("06:30"), emptySet()))
        assertEquals(LocalDateTime.parse("2026-10-25T00:05"), AlarmTimes.nextRing(justAfterMidnight, LocalTime.parse("00:05"), emptySet()))
    }

    @Test
    fun `a repeating alarm rings on the next of its days`() {
        // 24 October 2026 is a Saturday.
        assertEquals(LocalDateTime.parse("2026-10-26T07:00"), AlarmTimes.nextRing(justAfterMidnight, LocalTime.parse("07:00"), setOf(DayOfWeek.MONDAY)))
    }
}
