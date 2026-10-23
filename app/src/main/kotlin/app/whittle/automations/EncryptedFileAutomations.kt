package app.whittle.automations

import app.whittle.core.common.ContentCipher
import app.whittle.core.common.Outcome
import app.whittle.storage.EncryptedListFile
import java.io.File

/** The user's automations in one encrypted file (see [EncryptedListFile]). */
class EncryptedFileAutomations(file: File, cipher: ContentCipher) : AutomationBackend {
    private val store = EncryptedListFile(file, cipher, Automation.serializer(), ASSOCIATED_DATA, LABEL)

    override suspend fun load(): List<Automation> = store.load()

    override suspend fun save(automations: List<Automation>): Outcome<Unit> = store.save(automations)

    private companion object {
        val ASSOCIATED_DATA = "whittle-automations".encodeToByteArray()
        const val LABEL = "automations"
    }
}
