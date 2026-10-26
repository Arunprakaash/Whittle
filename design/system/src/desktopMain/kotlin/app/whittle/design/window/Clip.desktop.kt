package app.whittle.design.window

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry(StringSelection(text))

@OptIn(ExperimentalComposeUiApi::class)
actual suspend fun ClipEntry.plainText(): String? =
    nativeClipEntry.let { it as? java.awt.datatransfer.Transferable }?.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
        ?.getTransferData(DataFlavor.stringFlavor) as? String
