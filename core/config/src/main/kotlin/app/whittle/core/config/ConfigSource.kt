package app.whittle.core.config

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome

/** Name of a configuration document, relative to the config root (e.g. "policy-rules.json"). */
@JvmInline
value class ConfigName(val path: String)

/** Where configuration documents come from: bundled defaults, user overrides, or a test fixture. */
fun interface ConfigSource {
    fun read(name: ConfigName): Outcome<String>
}

/** Reads from the first source that has the document, so user overrides can shadow bundled defaults. */
class LayeredConfigSource(private val layers: List<ConfigSource>) : ConfigSource {
    override fun read(name: ConfigName): Outcome<String> {
        var lastFailure: Outcome.Failure? = null
        for (layer in layers) {
            when (val result = layer.read(name)) {
                is Outcome.Success -> return result
                is Outcome.Failure -> lastFailure = result
            }
        }
        return lastFailure ?: Outcome.Failure(HarnessError.Configuration("No config layers configured for ${name.path}"))
    }
}
