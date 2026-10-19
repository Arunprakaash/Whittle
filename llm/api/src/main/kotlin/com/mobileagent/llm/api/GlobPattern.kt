package com.mobileagent.llm.api

/** Minimal glob: `*` matches any run of characters, everything else is literal. */
class GlobPattern(pattern: String) {
    private val regex = Regex(pattern.split(WILDCARD).joinToString(".*") { Regex.escape(it) })

    fun matches(value: String): Boolean = regex.matches(value)

    private companion object {
        const val WILDCARD = "*"
    }
}
