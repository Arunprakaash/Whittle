package app.whittle.storage.runs

import android.util.Base64
import app.whittle.agent.RunCheckpoint
import app.whittle.agent.RunCheckpointStore
import app.whittle.agent.RunStatus
import app.whittle.core.common.ContentCipher
import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.flatMap
import app.whittle.core.common.map
import app.whittle.core.model.RunId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException

/** Persists run checkpoints in Room, encrypted at rest with [cipher]. */
class RoomRunCheckpointStore(
    private val dao: RunCheckpointDao,
    private val cipher: ContentCipher,
    private val clock: EpochClock,
    private val json: Json = Json,
) : RunCheckpointStore {
    override suspend fun save(checkpoint: RunCheckpoint): Outcome<Unit> {
        val plaintext = json.encodeToString(RunCheckpoint.serializer(), checkpoint).toByteArray(Charsets.UTF_8)
        return cipher.encrypt(plaintext, associatedData(checkpoint.runId)).flatMap { ciphertext ->
            storageCall {
                dao.upsert(
                    RunCheckpointEntity(
                        runId = checkpoint.runId.value,
                        status = checkpoint.status.name,
                        updatedAtMillis = clock.nowMillis(),
                        encryptedPayload = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
                    ),
                )
            }
        }
    }

    override suspend fun load(runId: RunId): Outcome<RunCheckpoint?> =
        storageCall { dao.find(runId.value) }.flatMap { entity -> entity?.let(::decode) ?: Outcome.Success(null) }

    /** Runs that can be resumed, newest first, e.g. after the app restarts. */
    suspend fun resumableRuns(): Outcome<List<RunCheckpoint>> =
        storageCall { dao.findByStatus(listOf(RunStatus.RUNNING.name, RunStatus.PAUSED.name)) }.flatMap { entities ->
            entities.fold<RunCheckpointEntity, Outcome<List<RunCheckpoint>>>(Outcome.Success(emptyList())) { decoded, entity ->
                decoded.flatMap { checkpoints -> decode(entity).map { checkpoints + it } }
            }
        }

    private fun decode(entity: RunCheckpointEntity): Outcome<RunCheckpoint> {
        val ciphertext = Base64.decode(entity.encryptedPayload, Base64.NO_WRAP)
        return cipher.decrypt(ciphertext, associatedData(RunId(entity.runId))).flatMap { plaintext ->
            try {
                Outcome.Success(json.decodeFromString(RunCheckpoint.serializer(), plaintext.toString(Charsets.UTF_8)))
            } catch (exception: SerializationException) {
                Outcome.Failure(HarnessError.Serialization("Unreadable checkpoint ${entity.runId}: ${exception.message}"))
            }
        }
    }

    private suspend fun <T> storageCall(block: suspend () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (exception: IOException) {
        Outcome.Failure(HarnessError.Storage("Run store unavailable: ${exception.javaClass.simpleName}"))
    } catch (exception: android.database.SQLException) {
        Outcome.Failure(HarnessError.Storage("Run store error: ${exception.javaClass.simpleName}"))
    }

    private fun associatedData(runId: RunId): ByteArray = runId.value.toByteArray(Charsets.UTF_8)
}
