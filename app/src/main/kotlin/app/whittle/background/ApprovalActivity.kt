package app.whittle.background

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import app.whittle.MainActivity
import app.whittle.WhittleApplication

/**
 * Approve or Decline on an approval card's notification. It has no screen: it answers the card as a tap in the chat would,
 * then closes. Android opens an activity from a notification only once the phone is unlocked, so a locked phone can't
 * answer; if it somehow still is, nothing is applied. A card already answered, or in a chat that has since closed, opens the
 * chat instead, where the user can see what happened.
 */
class ApprovalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getStringExtra(EXTRA_KEY)
        val approve = when (intent?.action) {
            ACTION_APPROVE -> true
            ACTION_DECLINE -> false
            else -> null
        }
        if (key != null && approve != null && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            val app = application as WhittleApplication
            app.notifications.clearApproval(key)
            if (!app.chat.answerDecision(key, approve)) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        finish()
    }

    companion object {
        const val ACTION_APPROVE = "app.whittle.action.APPROVE"
        const val ACTION_DECLINE = "app.whittle.action.DECLINE"
        const val EXTRA_KEY = "key"
    }
}
