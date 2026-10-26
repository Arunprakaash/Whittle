package app.whittle.design.window

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A computer window whose top the app draws itself, as a Mac window does with its title bar hidden: [insets] keep room for
 * the window's own buttons, and dragging a top bar inside [Draggable] moves the window. Null on the phone.
 */
interface WindowChrome {
    val insets: WindowInsets

    @Composable
    fun Draggable(content: @Composable () -> Unit)
}

val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/** [bar], able to move the window where the app draws the window's top. */
@Composable
fun WindowTopBar(bar: @Composable () -> Unit) {
    val chrome = LocalWindowChrome.current
    if (chrome != null) chrome.Draggable(bar) else bar()
}
