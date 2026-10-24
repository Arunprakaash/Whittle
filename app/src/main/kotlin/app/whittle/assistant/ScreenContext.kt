package app.whittle.assistant

import java.io.File

/**
 * What was on screen when the user called Whittle as their assistant: the app's name, its words and, when Android
 * allowed it, a screenshot. It goes to the model as untrusted content, as another app controls it.
 */
data class ScreenContext(val appName: String?, val text: String, val screenshot: File?)
