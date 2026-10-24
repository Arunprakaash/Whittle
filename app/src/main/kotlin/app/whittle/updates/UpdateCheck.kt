package app.whittle.updates

import app.whittle.core.common.EpochClock
import app.whittle.settings.UpdateDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Whittle is installed from GitHub, so nothing tells the user a new version is out. This asks GitHub's Releases for the
 * latest one, at most once every [UpdateDefaults.everyHours], and only while the user leaves it on. Only that request leaves
 * the phone: no id, nothing about the user. What it found is kept in [file], so a restart doesn't ask again.
 */
class UpdateCheck(
    private val http: OkHttpClient,
    private val defaults: UpdateDefaults,
    private val currentVersion: String,
    private val file: File,
    private val clock: EpochClock,
) {
    private val mutex = Mutex()
    private val _available = MutableStateFlow<Release?>(null)

    /** A release newer than this build, once a check has found one; null otherwise. */
    val available: StateFlow<Release?> = _available.asStateFlow()

    /**
     * Asks GitHub when the last check is older than [UpdateDefaults.everyHours], or when [force]d, and returns the newer
     * release to tell the user about: once per version, so a release already announced returns null. A failed request
     * changes nothing and is tried again next time.
     */
    suspend fun checkIfDue(force: Boolean = false): Release? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val saved = load()
            _available.value = saved.latest?.takeIf { isNewer(it.version, currentVersion) }
            val checkedAt = saved.checkedAtMillis
            if (!force && checkedAt != null && !due(checkedAt, clock.nowMillis(), defaults.everyHours)) return@withContext null
            val latest = fetch() ?: return@withContext null
            val newer = latest.takeIf { isNewer(it.version, currentVersion) }
            _available.value = newer
            val announce = newer?.takeIf { it.version != saved.announced }
            save(Saved(checkedAtMillis = clock.nowMillis(), latest = latest, announced = announce?.version ?: saved.announced))
            announce
        }
    }

    private fun fetch(): Release? = try {
        val request = Request.Builder().url(defaults.latestReleaseUrl).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.takeIf { response.isSuccessful } ?: return null
            parseLatestRelease(body.string(), Regex(defaults.apkPattern))
        }
    } catch (exception: IOException) {
        null
    }

    private fun load(): Saved = try {
        if (file.exists()) json.decodeFromString(Saved.serializer(), file.readText()) else Saved()
    } catch (exception: SerializationException) {
        Saved()
    } catch (exception: IllegalArgumentException) {
        Saved()
    } catch (exception: IOException) {
        Saved()
    }

    private fun save(saved: Saved) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Saved.serializer(), saved))
        } catch (exception: IOException) {
            // Only costs an extra check later.
        }
    }

    @Serializable
    private data class Saved(val checkedAtMillis: Long? = null, val latest: Release? = null, val announced: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Whether a check last made at [checkedAtMillis] is due again at [nowMillis]; a clock set back counts as due. */
fun due(checkedAtMillis: Long, nowMillis: Long, everyHours: Int): Boolean =
    nowMillis < checkedAtMillis || nowMillis - checkedAtMillis >= everyHours * MILLIS_PER_HOUR

private const val MILLIS_PER_HOUR = 3_600_000L
