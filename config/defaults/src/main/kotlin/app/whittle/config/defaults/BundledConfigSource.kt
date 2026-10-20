package app.whittle.config.defaults

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.config.ConfigName
import app.whittle.core.config.ConfigSource

/** The reviewed default configuration shipped inside the app, read from this module's resources. */
class BundledConfigSource(
    private val resourceRoot: String = DEFAULT_RESOURCE_ROOT,
) : ConfigSource {
    override fun read(name: ConfigName): Outcome<String> {
        val resourcePath = "$resourceRoot/${name.path}"
        val stream = BundledConfigSource::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: return Outcome.Failure(HarnessError.NotFound("Bundled config $resourcePath not found"))
        return Outcome.Success(stream.bufferedReader().use { it.readText() })
    }

    companion object {
        const val DEFAULT_RESOURCE_ROOT = "whittle/config"
    }
}
