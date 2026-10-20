package app.whittle.storage.chats

import android.content.Context
import android.util.Base64
import androidx.room.Room
import app.whittle.core.common.ContentCipher
import app.whittle.core.common.EpochClock
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.flatMap
import app.whittle.core.common.map
import java.io.IOException

/** Persists chats in Room, encrypted at rest with [cipher]; the chat id is bound in as associated data. */
class RoomChatStore(
    private val dao: ChatDao,
    private val cipher: ContentCipher,
    private val clock: EpochClock,
) : ChatStore {
    override suspend fun save(id: ChatId, document: String): Outcome<Unit> =
        cipher.encrypt(document.toByteArray(Charsets.UTF_8), associatedData(id)).flatMap { ciphertext ->
            storageCall {
                dao.upsert(ChatEntity(id.value, clock.nowMillis(), Base64.encodeToString(ciphertext, Base64.NO_WRAP)))
            }
        }

    override suspend fun load(id: ChatId): Outcome<StoredChat?> =
        storageCall { dao.find(id.value) }.flatMap { entity -> entity?.let(::decode) ?: Outcome.Success(null) }

    override suspend fun latest(): Outcome<StoredChat?> =
        storageCall { dao.latest() }.flatMap { entity -> entity?.let(::decode) ?: Outcome.Success(null) }

    private fun decode(entity: ChatEntity): Outcome<StoredChat> {
        val id = ChatId(entity.chatId)
        return cipher.decrypt(Base64.decode(entity.encryptedDocument, Base64.NO_WRAP), associatedData(id))
            .map { StoredChat(id, entity.updatedAtMillis, it.toString(Charsets.UTF_8)) }
    }

    private suspend fun <T> storageCall(block: suspend () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (exception: IOException) {
        Outcome.Failure(HarnessError.Storage("Chat store unavailable: ${exception.javaClass.simpleName}"))
    } catch (exception: android.database.SQLException) {
        Outcome.Failure(HarnessError.Storage("Chat store error: ${exception.javaClass.simpleName}"))
    }

    private fun associatedData(id: ChatId): ByteArray = id.value.toByteArray(Charsets.UTF_8)

    companion object {
        /** Opens the on-device chats database. Create one store per process. */
        fun open(context: Context, databaseName: String, cipher: ContentCipher, clock: EpochClock): RoomChatStore =
            RoomChatStore(Room.databaseBuilder(context, ChatsDatabase::class.java, databaseName).build().chats(), cipher, clock)
    }
}
