package app.whittle.chat.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// Android's own formatters, which follow the phone's language and its 12- or 24-hour clock.

@Composable
actual fun reopensAt(millis: Long): String {
    val flags = DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_WEEKDAY
    return DateUtils.formatDateTime(LocalContext.current, millis, flags)
}

@Composable
actual fun timeAgo(millis: Long, nowMillis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, nowMillis, DateUtils.MINUTE_IN_MILLIS).toString()
