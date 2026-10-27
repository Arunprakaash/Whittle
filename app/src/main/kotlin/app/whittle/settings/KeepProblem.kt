package app.whittle.settings

import app.whittle.core.common.HarnessError

/** Why keeping a suggestion failed, in the cases the user can do something about. */
enum class KeepProblem { Gone, Full, TooLong, Failed }

/**
 * Which problem a failed keep was, from the store's typed error; its message is written for the model, so the screen
 * picks its own words. Memory refuses words over [maxItemChars] as [HarnessError.Configuration].
 */
fun keepProblem(error: HarnessError, text: String, maxItemChars: Int): KeepProblem = when {
    error is HarnessError.NotFound -> KeepProblem.Gone
    error is HarnessError.Full -> KeepProblem.Full
    error is HarnessError.Configuration && text.trim().length > maxItemChars -> KeepProblem.TooLong
    else -> KeepProblem.Failed
}
