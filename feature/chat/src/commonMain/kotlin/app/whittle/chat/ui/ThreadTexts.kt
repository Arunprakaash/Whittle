package app.whittle.chat.ui

import androidx.compose.runtime.Composable
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** What a notice says. */
@Composable
fun noticeText(notice: ThreadRow.Notice): String = when (notice.kind) {
    NoticeKind.Stopped -> stringResource(Res.string.notice_stopped)
    NoticeKind.PausedBudget -> stringResource(Res.string.notice_paused_budget)
    NoticeKind.PausedSteps -> stringResource(Res.string.notice_paused_steps)
    NoticeKind.FailedKey -> stringResource(Res.string.notice_failed_key)
    NoticeKind.FailedNetwork -> stringResource(Res.string.notice_failed_network)
    NoticeKind.FailedProvider -> stringResource(Res.string.notice_failed_provider, notice.statusCode ?: 0)
    NoticeKind.FailedGeneric -> stringResource(Res.string.notice_failed_generic)
    NoticeKind.ClaudeCodeSignedOut -> stringResource(Res.string.notice_claude_code_signed_out)
    NoticeKind.ClaudeCodeLimit -> {
        val reopens = notice.resetsAtMillis?.let { reopensAt(it) }
        when {
            reopens != null && notice.limit != null -> stringResource(Res.string.notice_claude_code_limit_window, notice.limit, reopens)
            reopens != null -> stringResource(Res.string.notice_claude_code_limit_reset, reopens)
            else -> stringResource(Res.string.notice_claude_code_limit, notice.detail.orEmpty())
        }
    }
    NoticeKind.ClaudeCodeFailed -> stringResource(Res.string.notice_claude_code_failed, notice.detail.orEmpty())
}

/** The line under a step that didn't simply run or finish. */
@Composable
fun stepDetail(status: StepRowStatus): String? = when (status) {
    StepRowStatus.Blocked -> stringResource(Res.string.step_blocked)
    StepRowStatus.Failed -> stringResource(Res.string.step_failed)
    StepRowStatus.Stopped -> stringResource(Res.string.step_stopped)
    StepRowStatus.NeedsAccess -> stringResource(Res.string.step_needs_access)
    StepRowStatus.Running, StepRowStatus.Done -> null
}

/** "1:05": minutes and seconds between two moments. */
@Composable
fun elapsedText(fromMillis: Long, toMillis: Long): String {
    val seconds = ((toMillis - fromMillis) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return stringResource(Res.string.elapsed_time, seconds / SECONDS_PER_MINUTE, (seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0'))
}

/**
 * Which of a plan's steps a card lists, and how many it leaves for "+N more": everything up to the next step to start,
 * so the card shows what's done, what's under way and what's next, without the whole list of what's to come.
 */
fun planShown(steps: List<PlanRow>): Pair<List<PlanRow>, Int> {
    val lastStarted = steps.indexOfLast { it.state != PlanRowState.Pending }
    val upTo = (lastStarted + 1).coerceAtMost(steps.lastIndex)
    val shown = steps.take(upTo + 1)
    return shown to steps.size - shown.size
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
