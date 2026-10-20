package app.whittle.core.common

import java.util.UUID

/** Generates unique identifiers for runs, spans and tool calls. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random: IdGenerator = IdGenerator { UUID.randomUUID().toString() }
    }
}
