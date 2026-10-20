package app.whittle.storage.chats

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** One row per chat. Everything readable is encrypted: the title on its own, so the list stays cheap. */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val updatedAtMillis: Long,
    val encryptedDocument: String,
    /** Empty for chats saved before version 2. */
    @ColumnInfo(defaultValue = "") val encryptedTitle: String,
)

/** The list view: no documents loaded. */
data class ChatTitleRow(val chatId: String, val updatedAtMillis: Long, val encryptedTitle: String)

@Dao
interface ChatDao {
    @Upsert
    suspend fun upsert(entity: ChatEntity)

    @Query("SELECT * FROM chats WHERE chatId = :chatId")
    suspend fun find(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAtMillis DESC LIMIT 1")
    suspend fun latest(): ChatEntity?

    @Query("SELECT chatId, updatedAtMillis, encryptedTitle FROM chats ORDER BY updatedAtMillis DESC")
    suspend fun titles(): List<ChatTitleRow>

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun delete(chatId: String)
}

@Database(entities = [ChatEntity::class], version = 2, exportSchema = true)
abstract class ChatsDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao

    companion object {
        /** Version 2 stores each chat's title separately so the list doesn't decrypt whole chats. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN encryptedTitle TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
