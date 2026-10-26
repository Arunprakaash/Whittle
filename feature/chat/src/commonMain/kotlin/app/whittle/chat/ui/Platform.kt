package app.whittle.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry

/** Back, where the device has it, while [enabled]. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)

/** [text] for the clipboard, as plain text; [label] names it where the device shows one. */
expect fun plainTextClip(label: String, text: String): ClipEntry
