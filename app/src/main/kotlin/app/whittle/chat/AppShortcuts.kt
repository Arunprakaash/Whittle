package app.whittle.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import app.whittle.R
import app.whittle.quickstart.QuickStart
import app.whittle.storage.chats.ChatId

/**
 * Home-screen shortcuts to the apps Whittle runs. Each opens Whittle (through [QuickStart.ENTRY], which other apps can't start) at its chat with the app's preview, starting it again
 * when it isn't running, so an app the agent built is one tap away like any other. Android asks the user before adding one.
 */
class AppShortcuts(private val context: Context) {
    private val manager = context.getSystemService(ShortcutManager::class.java)

    /** Whether the launcher takes pinned shortcuts; most do. */
    val canPin: Boolean get() = manager.isRequestPinShortcutSupported

    /** Asks the launcher to add the app on [item], in chat [chat], to the home screen; false when it can't. */
    fun pin(chat: ChatId, item: ThreadItem.Process): Boolean {
        if (!manager.isRequestPinShortcutSupported) return false
        val intent = intent(chat.value, item.key)
        val shortcut = ShortcutInfo.Builder(context, shortcutId(chat, item.key))
            .setShortLabel(item.name)
            .setIcon(icon(item.name))
            .setIntent(intent)
            .build()
        return manager.requestPinShortcut(shortcut, null)
    }

    /**
     * Shortcuts pinned before apps opened through [QuickStart.ENTRY] point at the exported MainActivity, which no longer
     * honours them. Points each one at the alias, keeping its chat and card; only those that need it are touched.
     */
    fun migratePinned() {
        val stale = manager.pinnedShortcuts.filter { shortcut ->
            shortcut.id.startsWith("app:") && shortcut.intent?.let { it.action == ACTION_OPEN_APP && it.component?.className != QuickStart.ENTRY } == true
        }
        val updated = stale.mapNotNull { shortcut ->
            val old = shortcut.intent ?: return@mapNotNull null
            val chat = old.getStringExtra(EXTRA_CHAT) ?: return@mapNotNull null
            val card = old.getStringExtra(EXTRA_CARD) ?: return@mapNotNull null
            ShortcutInfo.Builder(context, shortcut.id).setIntent(intent(chat, card)).build()
        }
        if (updated.isNotEmpty()) manager.updateShortcuts(updated)
    }

    private fun intent(chat: String, card: String): Intent = Intent()
        .setComponent(ComponentName(context, QuickStart.ENTRY))
        .setAction(ACTION_OPEN_APP)
        .putExtra(EXTRA_CHAT, chat)
        .putExtra(EXTRA_CARD, card)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

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

        /**
         * The app a shortcut's [intent] opens, or null for any other intent, or one that didn't come through
         * [QuickStart.ENTRY]: MainActivity is exported, so another app could send the same action.
         */
        fun opening(intent: Intent?): ShortcutOpening? =
            intent?.let { opening(it.action, it.component?.className, it.getStringExtra(EXTRA_CHAT), it.getStringExtra(EXTRA_CARD)) }

        /** [opening] on the parts of an intent: its [action], the class it was sent to, and its two extras. */
        fun opening(action: String?, componentClass: String?, chat: String?, card: String?): ShortcutOpening? =
            if (action == ACTION_OPEN_APP && componentClass == QuickStart.ENTRY && chat != null && card != null) ShortcutOpening(ChatId(chat), card) else null
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
