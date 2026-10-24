package app.whittle.chat

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import app.whittle.MainActivity
import app.whittle.R
import app.whittle.storage.chats.ChatId

/**
 * Home-screen shortcuts to the apps Whittle runs. Each opens Whittle at its chat with the app's preview, starting it again
 * when it isn't running, so an app the agent built is one tap away like any other. Android asks the user before adding one.
 */
class AppShortcuts(private val context: Context) {
    private val manager = context.getSystemService(ShortcutManager::class.java)

    /** Whether the launcher takes pinned shortcuts; most do. */
    val canPin: Boolean get() = manager.isRequestPinShortcutSupported

    /** Asks the launcher to add the app on [item], in chat [chat], to the home screen; false when it can't. */
    fun pin(chat: ChatId, item: ThreadItem.Process): Boolean {
        if (!manager.isRequestPinShortcutSupported) return false
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_APP)
            .putExtra(EXTRA_CHAT, chat.value)
            .putExtra(EXTRA_CARD, item.key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val shortcut = ShortcutInfo.Builder(context, shortcutId(chat, item.key))
            .setShortLabel(item.name)
            .setIcon(icon(item.name))
            .setIntent(intent)
            .build()
        return manager.requestPinShortcut(shortcut, null)
    }

    /**
     * The chat [chat] was deleted, or every chat when null: its shortcuts stay on the home screen (only the user can remove
     * them) but are greyed out, saying why, instead of opening an empty Whittle.
     */
    fun forget(chat: ChatId?) {
        val gone = manager.pinnedShortcuts.map { it.id }.filter { id -> chatOf(id)?.let { chat == null || it == chat } == true }
        if (gone.isNotEmpty()) manager.disableShortcuts(gone, context.getString(R.string.shortcut_chat_deleted))
    }

    /** The app's first letter on Whittle's green, in the launcher's adaptive shape. */
    private fun icon(name: String): Icon {
        val bitmap = createBitmap(ICON_PX, ICON_PX)
        val canvas = Canvas(bitmap)
        canvas.drawColor(context.getColor(R.color.brand_pine))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(R.color.brand_paper)
            textSize = ICON_PX * LETTER_SHARE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val baseline = ICON_PX / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(iconLetter(name), ICON_PX / 2f, baseline, paint)
        return Icon.createWithAdaptiveBitmap(bitmap)
    }

    companion object {
        const val ACTION_OPEN_APP = "app.whittle.action.OPEN_APP"
        private const val EXTRA_CHAT = "chat"
        private const val EXTRA_CARD = "card"

        /** An adaptive icon is 108dp with the middle 72dp always shown; at 4x that is 432px. */
        private const val ICON_PX = 432

        /** The letter's size as a share of the icon, so it sits well inside the part every launcher shows. */
        private const val LETTER_SHARE = 0.3f

        /** The app a shortcut's [intent] opens, or null for any other intent. */
        fun opening(intent: Intent?): ShortcutOpening? {
            if (intent?.action != ACTION_OPEN_APP) return null
            val chat = intent.getStringExtra(EXTRA_CHAT) ?: return null
            val card = intent.getStringExtra(EXTRA_CARD) ?: return null
            return ShortcutOpening(ChatId(chat), card)
        }
    }
}

/** What a home-screen shortcut opens: the app on card [cardKey] in chat [chat]. */
data class ShortcutOpening(val chat: ChatId, val cardKey: String)

/** A shortcut's id: one per app card, so pinning the same app again updates it rather than adding a second. */
fun shortcutId(chat: ChatId, cardKey: String): String = "$SHORTCUT_PREFIX${chat.value}:$cardKey"

/** The chat an app shortcut's [id] belongs to, or null for an id that isn't one. */
fun chatOf(id: String): ChatId? = id.removePrefix(SHORTCUT_PREFIX).takeIf { id.startsWith(SHORTCUT_PREFIX) }?.substringBefore(':', "")?.takeIf { it.isNotEmpty() }?.let(::ChatId)

/** The letter an app's shortcut shows: the first letter or digit of its [name], or W when it has none. */
fun iconLetter(name: String): String = name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "W"

private const val SHORTCUT_PREFIX = "app:"
