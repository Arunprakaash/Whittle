package app.whittle.agent

import app.whittle.core.model.ContentBlock
import app.whittle.core.model.Provenance

/** Where output the user passes on comes from: a card's Ask to fix, or the screen when they call Whittle as their assistant. */
enum class PastedKind { APP_CRASH, PROCESS_OUTPUT, PREVIEW_CONSOLE, SCREEN }

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
    }
    return ContentBlock.Text(messages.diagnosticsUntrusted(source, text.takeLast(maxChars)), provenance = Provenance.Untrusted(source))
}
