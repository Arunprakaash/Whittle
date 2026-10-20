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

/**
 * Persists chats in Room, encrypted at rest with [cipher]. The chat id is bound in as associated data,
 * and the title uses a separate binding so a title can never be swapped in as a document.
 */
class RoomChatStore(
    private val dao: ChatDao,
    private val cipher: ContentCipher,
    private val clock: EpochClock,
) : ChatStore {
    override suspend fun save(id: ChatId, title: String, document: String): Outcome<Unit> =
        importChat(id, title, document, clock.nowMillis())

    override suspend fun importChat(id: ChatId, title: String, document: String, updatedAtMillis: Long): Outcome<Unit> =
        encrypt(document, documentBinding(id)).flatMap { encryptedDocument ->
            encrypt(title, titleBinding(id)).flatMap { encryptedTitle ->
                storageCall { dao.upsert(ChatEntity(id.value, updatedAtMillis, encryptedDocument, encryptedTitle)) }
            }
        }

    override suspend fun load(id: ChatId): Outcome<StoredChat?> =
        storageCall { dao.find(id.value) }.flatMap { entity -> entity?.let(::decode) ?: Outcome.Success(null) }

    override suspend fun latest(): Outcome<StoredChat?> =
        storageCall { dao.latest() }.flatMap { entity -> entity?.let(::decode) ?: Outcome.Success(null) }

    override suspend fun list(): Outcome<List<ChatSummary>> = storageCall { dao.titles() }.map { rows ->
        rows.map { row ->
            val id = ChatId(row.chatId)
            // A title that won't decrypt is shown as untitled rather than hiding the chat.
            val title = row.encryptedTitle.takeIf { it.isNotEmpty() }?.let { decrypt(it, titleBinding(id)) }
            ChatSummary(id, (title as? Outcome.Success)?.value, row.updatedAtMillis, row.pinned)
        }
    }

    override suspend fun delete(id: ChatId): Outcome<Unit> = storageCall { dao.delete(id.value) }

    override suspend fun setPinned(id: ChatId, pinned: Boolean): Outcome<Unit> = storageCall {
        if (pinned) dao.pin(ChatPinEntity(id.value)) else dao.unpin(id.value)
    }

    private fun decode(entity: ChatEntity): Outcome<StoredChat> {
        val id = ChatId(entity.chatId)
        return decrypt(entity.encryptedDocument, documentBinding(id)).map { StoredChat(id, entity.updatedAtMillis, it) }
    }

    private fun encrypt(text: String, binding: ByteArray): Outcome<String> =
        cipher.encrypt(text.toByteArray(Charsets.UTF_8), binding).map { Base64.encodeToString(it, Base64.NO_WRAP) }

    private fun decrypt(encoded: String, binding: ByteArray): Outcome<String> =
        cipher.decrypt(Base64.decode(encoded, Base64.NO_WRAP), binding).map { it.toString(Charsets.UTF_8) }

    private suspend fun <T> storageCall(block: suspend () -> T): Outcome<T> = try {
        Outcome.Success(block())
    } catch (exception: IOException) {
        Outcome.Failure(HarnessError.Storage("Chat store unavailable: ${exception.javaClass.simpleName}"))
    } catch (exception: android.database.SQLException) {
        Outcome.Failure(HarnessError.Storage("Chat store error: ${exception.javaClass.simpleName}"))
    }

    private fun documentBinding(id: ChatId): ByteArray = id.value.toByteArray(Charsets.UTF_8)

    private fun titleBinding(id: ChatId): ByteArray = (TITLE_BINDING_PREFIX + id.value).toByteArray(Charsets.UTF_8)

    companion object {
        private const val TITLE_BINDING_PREFIX = "title:"

        /** Opens the on-device chats database. Create one store per process. */
        fun open(context: Context, databaseName: String, cipher: ContentCipher, clock: EpochClock): RoomChatStore =
            RoomChatStore(
                Room.databaseBuilder(context, ChatsDatabase::class.java, databaseName)
                    .addMigrations(ChatsDatabase.MIGRATION_1_2, ChatsDatabase.MIGRATION_2_3)
                    .build()
                    .chats(),
                cipher,
                clock,
            )
    }
}
