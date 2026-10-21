package app.whittle.linux

import android.content.Context
import android.text.format.Formatter
import app.whittle.R

/** How setup's steps read, in the setup screens and the notification. */
object LinuxSteps {
    fun label(context: Context, step: LinuxSetupStep, done: Boolean, imageName: String): String = when (step) {
        LinuxSetupStep.Download -> context.getString(if (done) R.string.linux_step_downloaded else R.string.linux_step_download, imageName)
        LinuxSetupStep.Unpack -> context.getString(if (done) R.string.linux_step_unpacked else R.string.linux_step_unpack)
        LinuxSetupStep.Configure -> context.getString(if (done) R.string.linux_step_configured else R.string.linux_step_configure)
        LinuxSetupStep.BasePackages -> context.getString(if (done) R.string.linux_step_packages_done else R.string.linux_step_packages)
    }

    /** "12 MB of 30 MB" while downloading, a percentage while unpacking; null when there's no count to show. */
    fun detail(context: Context, state: LinuxState.SettingUp): String? = when {
        state.totalBytes <= 0 -> null
        state.step == LinuxSetupStep.Download -> context.getString(
            R.string.linux_progress_bytes,
            Formatter.formatShortFileSize(context, state.doneBytes),
            Formatter.formatShortFileSize(context, state.totalBytes),
        )
        else -> context.getString(R.string.linux_progress_percent, percent(state))
    }

    fun percent(state: LinuxState.SettingUp): Int =
        if (state.totalBytes <= 0) 0 else ((state.doneBytes * PERCENT) / state.totalBytes).toInt().coerceIn(0, PERCENT.toInt())

    private const val PERCENT = 100L
}
