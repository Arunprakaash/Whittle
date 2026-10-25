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

    private val _answeredAt = MutableStateFlow<Long?>(null)

    /** When GitHub last answered a check, so the user can see it's working; a failed check doesn't count. Null before any. */
    val answeredAt: StateFlow<Long?> = _answeredAt.asStateFlow()

    private val _lastFailed = MutableStateFlow(false)

    /** The latest check got no answer (no network, GitHub down or rate limited). */
    val lastFailed: StateFlow<Boolean> = _lastFailed.asStateFlow()

    /**
     * Asks GitHub when the last check is older than [UpdateDefaults.everyHours], or when [force]d, and returns the newer
     * release to tell the user about: once per version, so a release already announced returns null. A failed request,
     * a rate limit included, counts as a check too, so it is tried again at the next interval and not at every start.
     */
    suspend fun checkIfDue(force: Boolean = false): Release? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val saved = load()
            _available.value = saved.latest?.takeIf { isNewer(it.version, currentVersion) }
            _answeredAt.value = saved.answeredAtMillis
            _lastFailed.value = saved.checkedAtMillis != null && saved.checkedAtMillis != saved.answeredAtMillis
            // A release saved with no answer time was saved by an older version, before answers were recorded: check now,
            // so the user sees when it last worked. A first check that failed has no release, and waits as usual.
            val upgraded = saved.latest != null && saved.answeredAtMillis == null
            val checkedAt = saved.checkedAtMillis.takeUnless { upgraded }
            if (!force && checkedAt != null && !due(checkedAt, clock.nowMillis(), defaults.everyHours)) return@withContext null
            val latest = fetch()
            if (latest == null) {
                _lastFailed.value = true
                save(saved.copy(checkedAtMillis = clock.nowMillis()))
                return@withContext null
            }
            val newer = latest.takeIf { isNewer(it.version, currentVersion) }
            _available.value = newer
            val now = clock.nowMillis()
            _answeredAt.value = now
            _lastFailed.value = false
            val announce = newer?.takeIf { it.version != saved.announced }
            save(Saved(checkedAtMillis = now, answeredAtMillis = now, latest = latest, announced = announce?.version ?: saved.announced))
            announce
        }
    }

    private fun fetch(): Release? = try {
        val request = Request.Builder().url(defaults.latestReleaseUrl).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.takeIf { response.isSuccessful } ?: return null
            parseLatestRelease(body.string(), defaults.apkAsset)
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
    private data class Saved(
        /** The last check, answered or not; it sets when the next one is due. */
        val checkedAtMillis: Long? = null,
        /** The last check GitHub answered. */
        val answeredAtMillis: Long? = null,
        val latest: Release? = null,
        val announced: String? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Whether a check last made at [checkedAtMillis] is due again at [nowMillis]; a clock set back counts as due. */
fun due(checkedAtMillis: Long, nowMillis: Long, everyHours: Int): Boolean =
    nowMillis < checkedAtMillis || nowMillis - checkedAtMillis >= everyHours * MILLIS_PER_HOUR

private const val MILLIS_PER_HOUR = 3_600_000L
