package app.whittle.design.window

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry

/** Back, where the device has it, while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

/** [text] for the clipboard, as plain text; [label] names it where the device shows one. */
expect fun plainTextClip(label: String, text: String): ClipEntry

/** The clipboard's text, for a field's paste button; null when it holds none, or the device won't say. */
expect suspend fun ClipEntry.plainText(): String?
