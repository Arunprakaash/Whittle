package app.whittle.files.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable

// Android's own formatter, which follows the phone's language.
@Composable
actual fun timeAgo(millis: Long, nowMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
