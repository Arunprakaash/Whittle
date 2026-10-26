package app.whittle.design.window

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry(ClipData.newPlainText(label, text))

actual suspend fun ClipEntry.plainText(): String? = clipData.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
