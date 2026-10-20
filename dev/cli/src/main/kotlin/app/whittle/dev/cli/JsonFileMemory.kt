package app.whittle.dev.cli

import app.whittle.agent.Memory
import app.whittle.agent.MemoryBackend
import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** The command line's memory: a plain JSON file on the developer's own machine, kept between sessions. */
class JsonFileMemory(private val file: Path) : MemoryBackend {
    override suspend fun load(): List<Memory> = try {
        if (Files.isRegularFile(file)) json.decodeFromString(LIST, Files.readString(file)) else emptyList()
    } catch (exception: IOException) {
        emptyList()
    } catch (exception: SerializationException) {
        emptyList()
    }

    override suspend fun save(memories: List<Memory>): Outcome<Unit> = try {
        file.parent?.let(Files::createDirectories)
        Files.writeString(file, json.encodeToString(LIST, memories))
        Outcome.Success(Unit)
    } catch (exception: IOException) {
        Outcome.Failure(HarnessError.Storage("Couldn't save memory to $file: ${exception.message}"))
    }

    private companion object {
        val LIST = ListSerializer(Memory.serializer())
        val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}
