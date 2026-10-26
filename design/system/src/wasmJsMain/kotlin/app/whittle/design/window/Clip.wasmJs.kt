package app.whittle.design.window

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry.withPlainText(text)

// A page can't read the clipboard without asking; the field takes a paste from the keyboard instead.
actual suspend fun ClipEntry.plainText(): String? = null
