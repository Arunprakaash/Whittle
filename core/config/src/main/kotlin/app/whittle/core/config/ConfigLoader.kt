package app.whittle.core.config

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.flatMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Every configuration document carries a schema version so old files are rejected, not misread. */
interface VersionedConfig {
    val schemaVersion: Int
}

class ConfigLoader(
    private val source: ConfigSource,
    private val json: Json = ConfigJson.strict,
) {
    fun <T : VersionedConfig> load(
        name: ConfigName,
        serializer: KSerializer<T>,
        supportedSchemaVersion: Int,
    ): Outcome<T> = source.read(name).flatMap { text ->
        decode(name, text, serializer).flatMap { config ->
            if (config.schemaVersion == supportedSchemaVersion) {
                Outcome.Success(config)
            } else {
                Outcome.Failure(
                    HarnessError.Configuration(
                        "${name.path} has schema version ${config.schemaVersion}; expected $supportedSchemaVersion",
                    ),
                )
            }
        }
    }

    private fun <T> decode(name: ConfigName, text: String, serializer: KSerializer<T>): Outcome<T> = try {
        Outcome.Success(json.decodeFromString(serializer, text))
    } catch (exception: SerializationException) {
        Outcome.Failure(HarnessError.Configuration("${name.path} is invalid: ${exception.message}"))
    } catch (exception: IllegalArgumentException) {
        Outcome.Failure(HarnessError.Configuration("${name.path} is invalid: ${exception.message}"))
    }
}

object ConfigJson {
    /** Config files are reviewed like code, so unknown keys are an error rather than silently ignored. */
    val strict: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
}
