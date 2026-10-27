package app.whittle.automations

import app.whittle.core.common.ContentCipher
import app.whittle.core.common.Outcome
import app.whittle.storage.EncryptedListFile
import java.io.File

/** The events waiting to run, in one encrypted file (see [EncryptedListFile]), so a reboot doesn't lose them. */
class EncryptedFileEventQueue(file: File, cipher: ContentCipher) : EventQueueBackend {
    private val store = EncryptedListFile(file, cipher, QueuedEvents.serializer(), ASSOCIATED_DATA, LABEL)

    override suspend fun load(): List<QueuedEvents> = store.load()

    override suspend fun save(all: List<QueuedEvents>): Outcome<Unit> = store.save(all)

    private companion object {
        val ASSOCIATED_DATA = "whittle-automation-events".encodeToByteArray()
        const val LABEL = "automation events"
    }
}
