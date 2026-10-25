package app.whittle.settings

import app.whittle.core.common.HarnessError

/** Why keeping a suggestion failed, in the cases the user can do something about. */
enum class KeepProblem { Gone, Full, TooLong, Failed }

/**
 * Which problem a failed keep was. The store's messages are written for the model, so the screen picks its own words
 * from the reason: the suggestion is gone, the text is over the limit, memory is full, or it just didn't save.
 */
fun keepProblem(error: HarnessError, text: String, remembered: Int, maxItems: Int, maxItemChars: Int): KeepProblem = when {
    error is HarnessError.NotFound -> KeepProblem.Gone
    text.trim().length > maxItemChars -> KeepProblem.TooLong
    error is HarnessError.Configuration && remembered >= maxItems -> KeepProblem.Full
    else -> KeepProblem.Failed
}
