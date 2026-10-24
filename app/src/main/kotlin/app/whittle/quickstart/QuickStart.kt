package app.whittle.quickstart

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Canvas
import android.graphics.drawable.Icon
import androidx.core.graphics.createBitmap
import app.whittle.MainActivity
import app.whittle.R
import app.whittle.design.R as DesignR

/**
 * One tap from outside the app into a new chat: the launcher icon's long-press menu (New chat, Speak) and the Quick
 * Settings tile ([WhittleTileService]). Each opens [MainActivity] with one of these, which starts the chat.
 */
enum class QuickStart(val action: String) {
    /** A new, empty chat with the keyboard. */
    NEW_CHAT("app.whittle.action.NEW_CHAT"),

    /** A new chat already listening, as if the mic was tapped. */
    SPEAK("app.whittle.action.SPEAK"),
    ;

    /** Tells the launcher this one was used, so it can rank it; whether it came from the icon's menu or the tile. */
    fun reportUsed(context: Context) = context.getSystemService(ShortcutManager::class.java).reportShortcutUsed(id)

    private val id: String get() = name.lowercase()

    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    companion object {
        /** What [action] asks for, or null for an intent that isn't a quick start. */
        fun from(action: String?): QuickStart? = entries.firstOrNull { it.action == action }

        /**
         * Puts New chat and Speak in the launcher icon's long-press menu. Set from code rather than shortcuts.xml, which
         * would have to name the package and so break the debug build's; setting them again at each launch costs nothing.
         */
        fun publish(context: Context) {
            val manager = context.getSystemService(ShortcutManager::class.java)
            manager.dynamicShortcuts = listOf(
                shortcut(context, NEW_CHAT, R.string.quick_new_chat, DesignR.drawable.ms_edit_square),
                shortcut(context, SPEAK, R.string.quick_speak, DesignR.drawable.ms_mic),
            )
        }

        private fun shortcut(context: Context, start: QuickStart, label: Int, glyph: Int): ShortcutInfo =
            ShortcutInfo.Builder(context, start.id)
                .setShortLabel(context.getString(label))
                .setIcon(glyphIcon(context, glyph))
                .setIntent(start.intent(context))
                .build()

        /** The symbol [glyph] in Whittle's paper colour on its green, in the launcher's adaptive shape, like the app icon. */
        private fun glyphIcon(context: Context, glyph: Int): Icon {
            val bitmap = createBitmap(ICON_PX, ICON_PX)
            val canvas = Canvas(bitmap)
            canvas.drawColor(context.getColor(R.color.brand_pine))
            context.getDrawable(glyph)?.mutate()?.let { drawable ->
                drawable.setTint(context.getColor(R.color.brand_paper))
                val inset = (ICON_PX - GLYPH_PX) / 2
                drawable.setBounds(inset, inset, inset + GLYPH_PX, inset + GLYPH_PX)
                drawable.draw(canvas)
            }
            return Icon.createWithAdaptiveBitmap(bitmap)
        }

        /** An adaptive icon is 108dp with the middle 72dp always shown; at 4x that is 432px. */
        private const val ICON_PX = 432

        /** The symbol's size, well inside the part every launcher shows. */
        private const val GLYPH_PX = 160
    }
}
