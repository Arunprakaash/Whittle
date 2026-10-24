package app.whittle.agent

import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Provenance

/** Where output the user passes on comes from: a card's Ask to fix, the screen when they call Whittle as their assistant, or the phone events that started an automation. */
enum class PastedKind { APP_CRASH, PROCESS_OUTPUT, PREVIEW_CONSOLE, SCREEN, PHONE_EVENT }

/**
 * Output the user passes on with a card's Ask to fix, from the program called [name]: a built app's crash, a process's output or
 * a preview's console. That program controls its words, so they go to the model the way web pages and files do: untrusted
 * content, wrapped as data, which taints the run ([app.whittle.core.model.Message.carriesUntrustedContent]). They never become
 * the user's own words. Cut to [maxChars], keeping the end, which is nearest the failure.
 */
fun pastedOutput(messages: AgentMessages, kind: PastedKind, name: String, text: String, maxChars: Int): ContentBlock.Text {
    val source = when (kind) {
        PastedKind.APP_CRASH -> messages.diagnosticsFromAppCrash(name)
        PastedKind.PROCESS_OUTPUT -> messages.diagnosticsFromProcess(name)
        PastedKind.PREVIEW_CONSOLE -> messages.diagnosticsFromPreview(name)
        PastedKind.SCREEN -> messages.diagnosticsFromScreen(name)
        PastedKind.PHONE_EVENT -> messages.diagnosticsFromEvent(name)
    }
    return ContentBlock.Text(messages.diagnosticsUntrusted(source, text.takeLast(maxChars)), provenance = Provenance.Untrusted(source))
}

/**
 * The screen the user called the assistant over, from the app called [name], as it goes beside their words: its [text]
 * as [pastedOutput] when it has any, and its [screenshot] (a workspace path; the model's tools see it at [screenshotShownAt]),
 * with a note naming it as data. All of it is untrusted, the screenshot too ([ContentBlock.ImageFile.provenance]), as the
 * app on screen controls what it shows: so the run is tainted whenever any of the screen goes, even a screen with no words
 * (a game, a web view) or a screenshot alone.
 */
fun pastedScreen(messages: AgentMessages, name: String, text: String, screenshot: String?, screenshotShownAt: String?, maxChars: Int): PastedScreen {
    val source = messages.diagnosticsFromScreen(name)
    val words = text.takeIf { it.isNotBlank() }?.let { pastedOutput(messages, PastedKind.SCREEN, name, it, maxChars) }
    val note = screenshot?.let { ContentBlock.Text(messages.screenshotUntrusted(source, screenshotShownAt ?: it), provenance = Provenance.Untrusted(source)) }
    return PastedScreen(listOfNotNull(words, note), screenshot?.let { ContentBlock.ImageFile(it, Provenance.Untrusted(source)) })
}

/** What [pastedScreen] adds to a message: untrusted [text] blocks, and the [screenshot] to send in place of the plain photo. */
data class PastedScreen(val text: List<ContentBlock.Text>, val screenshot: ContentBlock.ImageFile?)
