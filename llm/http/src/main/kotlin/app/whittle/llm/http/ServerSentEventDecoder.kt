package app.whittle.llm.http

/**
 * Decodes Server-Sent Events line by line. Returns the event's data when a blank line ends an event.
 * Comment lines and non-data fields are ignored, as the SSE spec allows.
 */
class ServerSentEventDecoder {
    private val dataLines = mutableListOf<String>()

    fun feed(line: String): String? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(COMMENT_PREFIX)) return null
        if (line.startsWith(DATA_FIELD)) dataLines += line.removePrefix(DATA_FIELD).removePrefix(SPACE)
        return null
    }

    /** Call when the stream ends, in case the last event wasn't followed by a blank line. */
    fun flush(): String? {
        if (dataLines.isEmpty()) return null
        return dataLines.joinToString(separator = "\n").also { dataLines.clear() }
    }

    private companion object {
        const val COMMENT_PREFIX = ":"
        const val DATA_FIELD = "data:"
        const val SPACE = " "
    }
}
