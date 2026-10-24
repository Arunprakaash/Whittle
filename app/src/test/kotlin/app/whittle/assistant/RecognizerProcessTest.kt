package app.whittle.assistant

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class RecognizerProcessTest {
    private val services = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
        .getElementsByTagName("service")
        .let { list -> (0 until list.length).map { list.item(it) as Element } }
        .associateBy { it.getAttributeNS(ANDROID, "name") }

    @Test
    fun `the recognizer and the always-bound assistant service run in the process whose start-up Whittle skips`() {
        assertEquals(ForwardingRecognitionService.PROCESS, services.getValue(".assistant.ForwardingRecognitionService").getAttributeNS(ANDROID, "process"))
        assertEquals(ForwardingRecognitionService.PROCESS, services.getValue(".assistant.WhittleAssistantService").getAttributeNS(ANDROID, "process"))
    }

    @Test
    fun `the session that reads the screen runs in Whittle's own process, where the chat takes it`() {
        assertEquals("", services.getValue(".assistant.AssistSessionService").getAttributeNS(ANDROID, "process"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
