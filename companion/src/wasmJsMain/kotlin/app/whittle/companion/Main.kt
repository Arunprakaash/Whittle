package app.whittle.companion

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import app.whittle.design.tokens.loadWhittleFonts
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** The browser app. A token after the page's # connects at once; it's taken out of the address bar straight away. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val token = window.location.hash.removePrefix("#")
    if (token.isNotEmpty()) window.history.replaceState(null, "", window.location.pathname)
    MainScope().launch {
        // The phone's fonts, before anything is drawn with them.
        loadWhittleFonts()
        ComposeViewport {
            val scope = rememberCoroutineScope()
            val link = remember { PhoneLink(scope) }
            LaunchedEffect(Unit) { if (token.isNotBlank()) link.connect(PhoneAddress.Default.webSocket, token) }
            CompanionApp(link, token = token)
        }
    }
}
