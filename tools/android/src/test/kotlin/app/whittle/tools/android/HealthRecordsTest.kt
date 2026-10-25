package app.whittle.tools.android

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRecordsTest {
    private val bed = Instant.parse("2026-10-24T22:00:00Z")

    private fun night(vararg stages: Pair<Int, Long>): SleepSessionRecord {
        var at = bed
        val list = stages.map { (type, minutes) -> SleepSessionRecord.Stage(at, at.plusSeconds(minutes * 60).also { at = it }, type) }
        return SleepSessionRecord(bed, ZoneOffset.UTC, if (list.isEmpty()) bed.plusSeconds(8 * 3600) else at, ZoneOffset.UTC, Metadata.manualEntry(), stages = list)
    }

    @Test
    fun `a night without stages counts as asleep throughout`() {
        assertEquals(480, asleepMinutes(night()))
        assertEquals(emptyMap(), stageMinutes(night()))
    }

    @Test
    fun `with stages, awake time isn't sleep, and each stage is totalled`() {
        val night = night(
            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to 15,
            SleepSessionRecord.STAGE_TYPE_LIGHT to 200,
            SleepSessionRecord.STAGE_TYPE_DEEP to 90,
            SleepSessionRecord.STAGE_TYPE_AWAKE to 10,
            SleepSessionRecord.STAGE_TYPE_REM to 100,
            SleepSessionRecord.STAGE_TYPE_LIGHT to 40,
        )
        assertEquals(430, asleepMinutes(night))
        assertEquals(mapOf("awake" to 25L, "light" to 240L, "deep" to 90L, "rem" to 100L), stageMinutes(night))
    }

    @Test
    fun `workout types read as words`() {
        assertEquals("running", exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals("strength training", exerciseName(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING))
        assertEquals("other", exerciseName(-42))
    }
}
