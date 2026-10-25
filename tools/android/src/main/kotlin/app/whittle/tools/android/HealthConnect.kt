package app.whittle.tools.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.whittle.tools.api.CapabilityId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/** The kinds of health data Whittle may read, each with its own read permission. [id] is what the model names. */
enum class HealthKind(val id: String, val record: KClass<out Record>) {
    STEPS("steps", StepsRecord::class),
    DISTANCE("distance", DistanceRecord::class),
    ACTIVE_CALORIES("active calories", ActiveCaloriesBurnedRecord::class),
    WORKOUTS("workouts", ExerciseSessionRecord::class),
    SLEEP("sleep", SleepSessionRecord::class),
    HEART_RATE("heart rate", HeartRateRecord::class),
    RESTING_HEART_RATE("resting heart rate", RestingHeartRateRecord::class),
    WEIGHT("weight", WeightRecord::class),
    ;

    val permission: String get() = HealthPermission.getReadPermission(record)

    companion object {
        fun of(id: String): HealthKind? = entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

        val permissions: Set<String> get() = entries.mapTo(mutableSetOf()) { it.permission }
    }
}

/** Where Health Connect stands on this phone: ready, needing its app from Play first (Android 9 to 13), or not possible. */
enum class HealthAvailability { READY, NEEDS_APP, UNSUPPORTED }

/** What [HealthDataTool] reads through, so it can be tested without a phone. */
interface HealthSource {
    fun availability(): HealthAvailability

    /** The Health Connect permissions the user has granted Whittle; empty when it isn't ready. */
    suspend fun granted(): Set<String>

    /** Whether the user let Whittle read data from before it was allowed, or the phone has no such limit to lift. */
    suspend fun pastDataAllowed(): Boolean

    /** The [kind]'s numbers between [from] and [to], per day or, with [byHour], per hour, in [zone]. */
    suspend fun read(kind: HealthKind, from: Instant, to: Instant, byHour: Boolean, zone: ZoneId): JsonArray
}

/**
 * Android's Health Connect, read only: whether it's on this phone, what the user lets Whittle read, asking for more, and
 * the reads. On Android 14 and later it's part of the system; before that it's [providerPackage] from Play, opened with
 * [installUri] (which takes `{{package}}`). Reads return numbers, times and workout types, never text another app wrote.
 */
class HealthConnect(
    private val context: Context,
    private val providerPackage: String,
    private val installUri: String,
    private val maxRecords: Int,
) : HealthSource {
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context, providerPackage) }

    override fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context, providerPackage)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.READY
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NEEDS_APP
        else -> HealthAvailability.UNSUPPORTED
    }

    override suspend fun granted(): Set<String> =
        if (availability() != HealthAvailability.READY) emptySet() else client.permissionController.getGrantedPermissions()

    override suspend fun pastDataAllowed(): Boolean =
        !supports(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) || HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted()

    /** Whether [capability] (Health, or Health in the background) is granted; background counts as granted where it doesn't exist. */
    suspend fun isGranted(capability: CapabilityId): Boolean {
        val granted = granted()
        return when (capability) {
            AndroidCapabilities.HEALTH -> granted.any { it in HealthKind.permissions }
            AndroidCapabilities.HEALTH_BACKGROUND -> askable(capability).all { it in granted }
            else -> false
        }
    }

    /**
     * The permissions to show on Health Connect's screen for [capability]: every kind plus older data for Health, or
     * reading in the background. Ones this phone's Health Connect doesn't support are left out.
     */
    suspend fun askable(capability: CapabilityId): Set<String> = when (capability) {
        AndroidCapabilities.HEALTH -> HealthKind.permissions +
            setOfNotNull(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY.takeIf { supports(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) })
        AndroidCapabilities.HEALTH_BACKGROUND -> setOfNotNull(
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND.takeIf { supports(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) },
        )
        else -> emptySet()
    }

    /** Health Connect's own permission screen, which takes [askable] permissions and returns the granted ones. */
    fun requestContract(): ActivityResultContract<Set<String>, Set<String>> = PermissionController.createRequestPermissionResultContract(providerPackage)

    /** Opens Play on Health Connect, for Android 9 to 13 where it's an app of its own. */
    fun installIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(installUri.replace(PACKAGE, providerPackage))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private suspend fun supports(feature: Int): Boolean =
        availability() == HealthAvailability.READY &&
            client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    override suspend fun read(kind: HealthKind, from: Instant, to: Instant, byHour: Boolean, zone: ZoneId): JsonArray = when (kind) {
        HealthKind.STEPS -> totals(StepsRecord.COUNT_TOTAL, from, to, byHour, zone) { put("steps", it) }
        HealthKind.DISTANCE -> totals(DistanceRecord.DISTANCE_TOTAL, from, to, byHour, zone) { put("km", round(it.inKilometers, 2)) }
        HealthKind.ACTIVE_CALORIES -> totals(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, from, to, byHour, zone) { put("kcal", it.inKilocalories.roundToLong()) }
        HealthKind.RESTING_HEART_RATE -> totals(RestingHeartRateRecord.BPM_AVG, from, to, byHour, zone) { put("restingBpm", it) }
        HealthKind.HEART_RATE -> buildJsonArray {
            grouped(setOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX), from, to, byHour, zone).forEach { (start, result) ->
                val avg = result[HeartRateRecord.BPM_AVG] ?: return@forEach
                add(buildJsonObject { put(start.first, start.second); put("minBpm", result[HeartRateRecord.BPM_MIN]); put("avgBpm", avg); put("maxBpm", result[HeartRateRecord.BPM_MAX]) })
            }
        }
        HealthKind.WORKOUTS -> buildJsonArray {
            records(ExerciseSessionRecord::class, from, to).forEach { session ->
                add(
                    buildJsonObject {
                        put("type", exerciseName(session.exerciseType))
                        put("start", local(session.startTime, zone))
                        put("end", local(session.endTime, zone))
                        put("minutes", Duration.between(session.startTime, session.endTime).toMinutes())
                        distanceKm(session.startTime, session.endTime)?.let { put("km", it) }
                    },
                )
            }
        }
        HealthKind.SLEEP -> buildJsonArray {
            // Health Connect matches a night by when it starts; a night belongs to the morning it ends, so one that
            // began the evening before [from] counts too.
            records(SleepSessionRecord::class, from.minus(NIGHT_LOOKBACK), to).filter { it.endTime.isAfter(from) }.forEach { night ->
                add(
                    buildJsonObject {
                        put("start", local(night.startTime, zone))
                        put("end", local(night.endTime, zone))
                        put("asleepMinutes", asleepMinutes(night))
                        stageMinutes(night).takeIf { it.isNotEmpty() }?.let { stages -> put("stageMinutes", buildJsonObject { stages.forEach { (name, minutes) -> put(name, minutes) } }) }
                    },
                )
            }
        }
        HealthKind.WEIGHT -> buildJsonArray {
            records(WeightRecord::class, from, to).forEach { weighIn ->
                add(buildJsonObject { put("time", local(weighIn.time, zone)); put("kg", round(weighIn.weight.inKilograms, 1)) })
            }
        }
    }

    /** One row per day or hour that has a [metric] value, written by [put]. */
    private suspend fun <T : Any> totals(
        metric: AggregateMetric<T>,
        from: Instant,
        to: Instant,
        byHour: Boolean,
        zone: ZoneId,
        put: JsonObjectBuilder.(T) -> Unit,
    ): JsonArray = buildJsonArray {
        grouped(setOf(metric), from, to, byHour, zone).forEach { (start, result) ->
            val value = result[metric] ?: return@forEach
            add(buildJsonObject { put(start.first, start.second); put(value) })
        }
    }

    /** Health Connect's per-day or per-hour groups, each keyed ("date" or "hour") by when it starts in [zone]. */
    private suspend fun grouped(
        metrics: Set<AggregateMetric<*>>,
        from: Instant,
        to: Instant,
        byHour: Boolean,
        zone: ZoneId,
    ): List<Pair<Pair<String, String>, AggregationResult>> = if (byHour) {
        client.aggregateGroupByDuration(AggregateGroupByDurationRequest(metrics, TimeRangeFilter.between(from, to), Duration.ofHours(1)))
            .map { (HOUR to local(it.startTime, zone)) to it.result }
    } else {
        val range = TimeRangeFilter.between(LocalDateTime.ofInstant(from, zone), LocalDateTime.ofInstant(to, zone))
        client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(metrics, range, Period.ofDays(1)))
            .map { (DATE to it.startTime.toLocalDate().toString()) to it.result }
    }

    private suspend fun <T : Record> records(type: KClass<T>, from: Instant, to: Instant): List<T> =
        client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), ascendingOrder = true, pageSize = maxRecords)).records

    /** A workout's distance, when the user lets Whittle read distance and something recorded it. */
    private suspend fun distanceKm(start: Instant, end: Instant): Double? = try {
        client.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), TimeRangeFilter.between(start, end)))[DistanceRecord.DISTANCE_TOTAL]
            ?.let { round(it.inKilometers, 2) }
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        const val PACKAGE = "{{package}}"
        val NIGHT_LOOKBACK: Duration = Duration.ofHours(24)
        const val DATE = "date"
        const val HOUR = "hour"
    }
}

/** Minutes asleep in [night]: its sleeping stages when it has them, otherwise the whole session. */
internal fun asleepMinutes(night: SleepSessionRecord): Long =
    if (night.stages.isEmpty()) {
        Duration.between(night.startTime, night.endTime).toMinutes()
    } else {
        night.stages.filter { it.stage in ASLEEP_STAGES }.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
    }

/** Minutes per named stage (light, deep, rem, awake), for devices that record stages. */
internal fun stageMinutes(night: SleepSessionRecord): Map<String, Long> =
    night.stages.groupBy { STAGE_NAMES[it.stage] ?: OTHER }
        .mapValues { (_, stages) -> stages.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() } }

/** A workout type as words ("running", "strength training"), from Health Connect's own names. */
internal fun exerciseName(type: Int): String =
    ExerciseSessionRecord.EXERCISE_TYPE_INT_TO_STRING_MAP[type]?.replace('_', ' ') ?: OTHER

private val ASLEEP_STAGES = setOf(
    SleepSessionRecord.STAGE_TYPE_SLEEPING,
    SleepSessionRecord.STAGE_TYPE_LIGHT,
    SleepSessionRecord.STAGE_TYPE_DEEP,
    SleepSessionRecord.STAGE_TYPE_REM,
)

private val STAGE_NAMES = mapOf(
    SleepSessionRecord.STAGE_TYPE_SLEEPING to "asleep",
    SleepSessionRecord.STAGE_TYPE_LIGHT to "light",
    SleepSessionRecord.STAGE_TYPE_DEEP to "deep",
    SleepSessionRecord.STAGE_TYPE_REM to "rem",
    SleepSessionRecord.STAGE_TYPE_AWAKE to "awake",
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED to "awake",
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED to "out of bed",
)

private const val OTHER = "other"

/** [instant] as a local date-time in [zone], to the minute. */
internal fun local(instant: Instant, zone: ZoneId): String = LocalDateTime.ofInstant(instant, zone).withSecond(0).withNano(0).toString()

private fun round(value: Double, places: Int): Double {
    val scale = Math.pow(10.0, places.toDouble())
    return Math.round(value * scale) / scale
}
