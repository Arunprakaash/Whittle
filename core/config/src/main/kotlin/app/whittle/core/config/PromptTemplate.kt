package app.whittle.core.config

import app.whittle.core.common.HarnessError
import app.whittle.core.common.Outcome
import app.whittle.core.common.map

/** A versioned prompt document with `{{name}}` placeholders. Every placeholder must be supplied. */
class PromptTemplate(val name: ConfigName, private val text: String) {
    val placeholders: Set<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

    fun render(variables: Map<String, String>): Outcome<String> {
        val missing = placeholders - variables.keys
        if (missing.isNotEmpty()) {
            return Outcome.Failure(HarnessError.Configuration("${name.path} is missing values for $missing"))
        }
        return Outcome.Success(PLACEHOLDER.replace(text) { match -> variables.getValue(match.groupValues[1]) })
    }

    companion object {
        // Every brace is escaped: Android's ICU regex engine rejects a bare "}", which the JVM tolerates.
        private val PLACEHOLDER = Regex("""\{\{\s*([a-zA-Z][a-zA-Z0-9_]*)\s*\}\}""")

        fun load(source: ConfigSource, name: ConfigName): Outcome<PromptTemplate> =
            source.read(name).map { PromptTemplate(name, it) }
    }
}
