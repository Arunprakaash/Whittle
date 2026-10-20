package app.whittle.storage.chats

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
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

/** A pinned chat. Kept apart from [ChatEntity] so saving a chat can never unpin it. */
@Entity(tableName = "chat_pins")
data class ChatPinEntity(@PrimaryKey val chatId: String)

/** The list view: no documents loaded. */
data class ChatTitleRow(val chatId: String, val updatedAtMillis: Long, val encryptedTitle: String, val pinned: Boolean)

@Dao
interface ChatDao {
    @Upsert
    suspend fun upsert(entity: ChatEntity)

    @Query("SELECT * FROM chats WHERE chatId = :chatId")
    suspend fun find(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAtMillis DESC LIMIT 1")
    suspend fun latest(): ChatEntity?

    @Query(
        "SELECT chats.chatId, updatedAtMillis, encryptedTitle, chat_pins.chatId IS NOT NULL AS pinned " +
            "FROM chats LEFT JOIN chat_pins ON chats.chatId = chat_pins.chatId ORDER BY updatedAtMillis DESC",
    )
    suspend fun titles(): List<ChatTitleRow>

    /** Removes the chat and its pin together. */
    @Transaction
    suspend fun delete(chatId: String) {
        deleteChat(chatId)
        unpin(chatId)
    }

    @Query("DELETE FROM chats WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun pin(pin: ChatPinEntity)

    @Query("DELETE FROM chat_pins WHERE chatId = :chatId")
    suspend fun unpin(chatId: String)
}

@Database(entities = [ChatEntity::class, ChatPinEntity::class], version = 3, exportSchema = true)
abstract class ChatsDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao

    companion object {
        /** Version 2 stores each chat's title separately so the list doesn't decrypt whole chats. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chats ADD COLUMN encryptedTitle TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Version 3 adds pinned chats. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `chat_pins` (`chatId` TEXT NOT NULL, PRIMARY KEY(`chatId`))")
            }
        }
    }
}
