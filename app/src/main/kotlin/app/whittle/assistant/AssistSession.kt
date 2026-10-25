package app.whittle.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import android.view.View
import androidx.annotation.RequiresApi
import app.whittle.MainActivity
import app.whittle.settings.AssistantDefaults
import java.io.File
import java.io.IOException

/**
 * One call of the assistant. Android hands over the screen's structure and, if the user allows it in Android's assist
 * settings, a screenshot; the session reads the words, keeps the screenshot as a file, passes both to [deliver] and opens
 * Whittle in a new chat about them. It never shows a window of its own. What doesn't arrive within
 * [AssistantDefaults.waitMillis] is left out.
 */
class AssistSession(
    context: Context,
    private val defaults: AssistantDefaults,
    private val maxChars: Int,
    private val deliver: (ScreenContext) -> Unit,
) : VoiceInteractionSession(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var waiting = 0
    private var appName: String? = null
    private var text = ""
    private var screenshot: File? = null
    private var opened = false

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        opened = false
        appName = null
        text = ""
        screenshot = null
        waiting = listOf(SHOW_WITH_ASSIST, SHOW_WITH_SCREENSHOT).count { showFlags and it != 0 }
        if (waiting == 0) open() else handler.postDelayed(::open, defaults.waitMillis)
    }

    /**
     * Android 10+: one call per app on screen, the focused one first (index 0). Only that one is read, so in split screen
     * the other app's words can't replace it or open the chat before the screenshot. It counts as arrived with or without
     * data.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onHandleAssist(state: AssistState) {
        if (state.index != 0) return
        read(state.assistStructure)
        arrived()
    }

    /** Before Android 10: the focused app. */
    @Deprecated("Android 10+ calls onHandleAssist(AssistState) instead.")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        read(structure)
        arrived()
    }

    /** Before Android 10: the other apps on screen, which aren't read. */
    @Deprecated("Android 10+ calls onHandleAssist(AssistState) instead.")
    override fun onHandleAssistSecondary(data: Bundle?, structure: AssistStructure?, content: AssistContent?, index: Int, count: Int) = Unit

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        // Too late for the chat, which has opened without it: nothing of the screen is kept.
        if (opened) return
        if (screenshot != null) this.screenshot = save(screenshot)
        arrived()
    }

    override fun onDestroy() {
        // Closed before the chat opened: the screenshot was never handed over, so it goes now.
        handler.removeCallbacksAndMessages(null)
        if (!opened) screenshot?.delete()
        super.onDestroy()
    }

    private fun read(structure: AssistStructure?) {
        structure ?: return
        appName = structure.activityComponent?.packageName?.let(::label)
        text = screenText((0 until structure.windowNodeCount).map { structure.getWindowNodeAt(it).rootViewNode.toScreenNode() }, maxChars)
    }

    private fun arrived() {
        waiting--
        if (waiting <= 0) open()
    }

    private fun open() {
        if (opened) return
        opened = true
        handler.removeCallbacksAndMessages(null)
        deliver(ScreenContext(appName, text, screenshot))
        startAssistantActivity(Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        hide()
    }

    /** A file of this call's own, so a second call while the first is still being attached can't replace or delete it. */
    private fun save(bitmap: Bitmap): File? {
        val name = defaults.screenshotFileName
        var file: File? = null
        return try {
            File.createTempFile("${name.substringBeforeLast('.')}-", ".${name.substringAfterLast('.')}", context.cacheDir).also {
                file = it
                it.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            }
        } catch (exception: IOException) {
            file?.delete()
            null
        }
    }

    private fun label(packageName: String): String? = try {
        context.packageManager.run { getApplicationLabel(getApplicationInfo(packageName, 0)).toString() }
    } catch (exception: PackageManager.NameNotFoundException) {
        null
    }

    private fun AssistStructure.ViewNode.toScreenNode(): ScreenNode = ScreenNode(
        text = text?.toString(),
        password = isPassword(),
        children = (0 until childCount).map { getChildAt(it).toScreenNode() },
    )

    private fun AssistStructure.ViewNode.isPassword(): Boolean {
        val variation = inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION)
        return variation in PASSWORD_TYPES || autofillHints.orEmpty().any { it == View.AUTOFILL_HINT_PASSWORD }
    }

    private companion object {
        const val JPEG_QUALITY = 90
        val PASSWORD_TYPES = setOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
    }
}
