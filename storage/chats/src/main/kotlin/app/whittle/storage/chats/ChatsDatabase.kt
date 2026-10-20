package app.whittle.storage.chats

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** One row per chat. Everything readable, title included, is inside [encryptedDocument]. */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val chatId: String,
    val updatedAtMillis: Long,
    val encryptedDocument: String,
)

@Dao
interface ChatDao {
    @Upsert
    suspend fun upsert(entity: ChatEntity)

    @Query("SELECT * FROM chats WHERE chatId = :chatId")
    suspend fun find(chatId: String): ChatEntity?

    @Query("SELECT * FROM chats ORDER BY updatedAtMillis DESC LIMIT 1")
    suspend fun latest(): ChatEntity?
}

@Database(entities = [ChatEntity::class], version = 1, exportSchema = true)
abstract class ChatsDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
}
