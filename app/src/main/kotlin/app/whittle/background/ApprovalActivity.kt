package app.whittle.background

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import app.whittle.MainActivity
import app.whittle.WhittleApplication
import app.whittle.storage.chats.ChatId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Approve or Decline on an approval card's notification. It has no screen: it answers the card as a tap in the chat would,
 * then closes. Android opens an activity from a notification only once the phone is unlocked, so a locked phone can't
 * answer; if it somehow still is, nothing is applied. A card already answered, in a chat that has since closed, or that only
 * the app may answer, opens the chat instead, where the user can see what happened.
 */
class ApprovalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val answer = NotificationAnswer.from(
            action = intent?.action,
            chat = intent?.getStringExtra(EXTRA_CHAT),
            key = intent?.getStringExtra(EXTRA_KEY),
            locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        )
        if (answer == null) {
            finish()
            return
        }
        val app = application as WhittleApplication
        app.notifications.clearApproval(answer.chat, answer.key)
        // A chat that has gone quiet is loaded first. This screenless activity stays until then, so opening Whittle
        // instead still starts from an activity of Whittle's own.
        app.graph.appScope.launch(Dispatchers.Main) {
            if (!app.chat.answerDecision(answer.chat, answer.key, answer.approve)) {
                startActivity(Intent(this@ApprovalActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            finish()
        }
    }

    companion object {
        const val ACTION_APPROVE = "app.whittle.action.APPROVE"
        const val ACTION_DECLINE = "app.whittle.action.DECLINE"
        const val EXTRA_CHAT = "chat"
        const val EXTRA_KEY = "key"
    }
}

/** What a tap on a notification's Approve or Decline asks for: [approve] the card [key] in [chat]. */
data class NotificationAnswer(val chat: ChatId, val key: String, val approve: Boolean) {
    companion object {
        /** The answer an [ApprovalActivity] intent carries; null when anything is missing or the phone is [locked]. */
        fun from(action: String?, chat: String?, key: String?, locked: Boolean): NotificationAnswer? {
            val approve = when (action) {
                ApprovalActivity.ACTION_APPROVE -> true
                ApprovalActivity.ACTION_DECLINE -> false
                else -> return null
            }
            if (locked || chat.isNullOrEmpty() || key.isNullOrEmpty()) return null
            return NotificationAnswer(ChatId(chat), key, approve)
        }
    }
}
