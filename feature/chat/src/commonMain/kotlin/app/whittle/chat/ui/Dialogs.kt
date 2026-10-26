package app.whittle.chat.ui

import androidx.compose.ui.window.DialogProperties

/** A dialog over the whole screen, system bars included where there are any. */
expect fun fullScreenDialog(): DialogProperties
