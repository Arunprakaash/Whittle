package com.mobileagent.storage.runs

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** One row per run. [encryptedPayload] is the whole checkpoint, encrypted and base64-encoded. */
@Entity(tableName = "run_checkpoints")
data class RunCheckpointEntity(
    @PrimaryKey val runId: String,
    val status: String,
    val updatedAtMillis: Long,
    val encryptedPayload: String,
)

@Dao
interface RunCheckpointDao {
    @Upsert
    suspend fun upsert(entity: RunCheckpointEntity)

    @Query("SELECT * FROM run_checkpoints WHERE runId = :runId")
    suspend fun find(runId: String): RunCheckpointEntity?

    @Query("SELECT * FROM run_checkpoints WHERE status IN (:statuses) ORDER BY updatedAtMillis DESC")
    suspend fun findByStatus(statuses: List<String>): List<RunCheckpointEntity>
}

@Database(entities = [RunCheckpointEntity::class], version = 1, exportSchema = true)
abstract class RunsDatabase : RoomDatabase() {
    abstract fun runCheckpoints(): RunCheckpointDao
}
