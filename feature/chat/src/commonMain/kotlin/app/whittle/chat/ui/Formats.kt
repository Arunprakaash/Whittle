package app.whittle.chat.ui

import androidx.compose.runtime.Composable

/** When a Claude plan limit reopens: "2:00 PM" today, with the day when it's later than that. */
@Composable
expect fun reopensAt(millis: Long): String
