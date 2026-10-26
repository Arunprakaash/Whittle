package app.whittle.chat.ui

/** The footer under a turn's final answer: what Copy copies, and the pages the turn cites. */
data class TurnFooter(
    val copyText: String,
    val sources: List<SourceRow>,
    /** The sources item whose expanded state the footer shows and toggles; null when the turn cites nothing. */
    val sourcesKey: String?,
    val sourcesExpanded: Boolean,
)

/** Which answers get a footer, and which sources items fold into one instead of showing on their own. */
data class FooterPlan(val footers: Map<String, TurnFooter>, val folded: Set<String>)

/**
 * One footer per message the user sends. A turn can run several times (a question or approval card continues
 * it), and each run ends with words of its own; only the last gets the footer. Copy takes all of the turn's
 * answers, and its sources from every run are merged.
 */
object AnswerFooters {
    fun plan(items: List<ThreadRow>): FooterPlan {
        val footers = mutableMapOf<String, TurnFooter>()
        val folded = mutableSetOf<String>()
        turns(items).forEach { turn ->
            val answers = turn.filterIsInstance<ThreadRow.Narration>().filter { it.answer && !it.streaming }
            val last = answers.lastOrNull() ?: return@forEach
            val sourceItems = turn.filterIsInstance<ThreadRow.Sources>()
            folded += sourceItems.map { it.key }
            footers[last.key] = TurnFooter(
                copyText = answers.joinToString(PARAGRAPH_BREAK) { it.text.trim() },
                sources = sourceItems.flatMap { it.sources }.distinctBy { it.url },
                sourcesKey = sourceItems.lastOrNull()?.key,
                sourcesExpanded = sourceItems.lastOrNull()?.expanded == true,
            )
        }
        return FooterPlan(footers, folded)
    }

    /** Split at each message the user sends; instructions added mid-run stay in the turn they joined. */
    private fun turns(items: List<ThreadRow>): List<List<ThreadRow>> =
        items.fold(mutableListOf<MutableList<ThreadRow>>()) { turns, item ->
            if ((item is ThreadRow.User && !item.steer) || turns.isEmpty()) turns += mutableListOf<ThreadRow>()
            turns.last() += item
            turns
        }

    private const val PARAGRAPH_BREAK = "\n\n"
}
