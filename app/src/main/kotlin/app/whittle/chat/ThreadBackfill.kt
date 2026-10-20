package app.whittle.chat

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Chats saved before answers, sources and step sites were recorded get them when they open, rebuilt from
 * what was saved: each turn's last words, the links in them, and the address in each step's details. Page titles from
 * the provider weren't saved then, so those sources show the link text or the site name.
 */
internal class ThreadBackfill(
    /** Step arguments that hold a page address (from the tool presentation config). */
    private val siteArguments: Set<String>,
    private val newKey: () -> String,
) {
    fun apply(items: List<ThreadItem>): List<ThreadItem> = turns(items.map(::withSites)).map(::withAnswer).flatMap(::withSources)

    private fun withSites(item: ThreadItem): ThreadItem = when (item) {
        is ThreadItem.Steps -> item.copy(steps = item.steps.map { step -> if (step.site != null) step else step.copy(site = siteOf(step)) })
        is ThreadItem.Summary -> item.copy(folded = item.folded.map(::withSites))
        else -> item
    }

    /** The details start with the step's arguments, one `name: value` line each, before a blank line. */
    private fun siteOf(step: StepModel): String? =
        step.inspection.orEmpty().lineSequence().takeWhile { it.isNotBlank() }
            .mapNotNull { line ->
                val name = line.substringBefore(FIELD_SEPARATOR, missingDelimiterValue = "")
                if (name !in siteArguments) return@mapNotNull null
                line.substringAfter(FIELD_SEPARATOR).trim().toHttpUrlOrNull()?.host?.removePrefix(WWW_PREFIX)
            }
            .firstOrNull()

    /** One user message and everything that answered it. */
    private fun turns(items: List<ThreadItem>): List<List<ThreadItem>> =
        items.fold(mutableListOf<MutableList<ThreadItem>>()) { turns, item ->
            // Instructions added mid-run (steers) belong to the turn they interrupted.
            if ((item is ThreadItem.User && !item.steer) || turns.isEmpty()) turns += mutableListOf<ThreadItem>()
            turns.last() += item
            turns
        }

    private fun withAnswer(turn: List<ThreadItem>): List<ThreadItem> {
        if (turn.any { it is ThreadItem.Narration && it.answer }) return turn
        val last = turn.indexOfLast { it is ThreadItem.Narration }
        if (last < 0) return turn
        return turn.mapIndexed { index, item -> if (index == last) (item as ThreadItem.Narration).copy(answer = true) else item }
    }

    private fun withSources(turn: List<ThreadItem>): List<ThreadItem> {
        if (turn.any { it is ThreadItem.Sources }) return turn
        val answer = turn.indexOfLast { it is ThreadItem.Narration }
        if (answer < 0) return turn
        val said = turn.flatMap { item ->
            when (item) {
                is ThreadItem.Narration -> listOf(item.text)
                is ThreadItem.Summary -> item.folded.filterIsInstance<ThreadItem.Narration>().map { it.text }
                else -> emptyList()
            }
        }
        val sources = CitedSources.from(said, cited = emptyMap())
        if (sources.isEmpty()) return turn
        return turn.take(answer + 1) + ThreadItem.Sources(newKey(), sources) + turn.drop(answer + 1)
    }

    private companion object {
        const val FIELD_SEPARATOR = ": "
        const val WWW_PREFIX = "www."
    }
}
