package app.whittle.assistant

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Whether Whittle is the phone's digital assistant, and the way to Android's screen for choosing one. */
object AssistantRole {
    fun held(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } else {
        Settings.Secure.getString(context.contentResolver, ASSISTANT_SETTING)?.startsWith(context.packageName + "/") == true
    }

    /** Android lets no app ask for the assistant role, so this opens where the user picks one. */
    fun choose(context: Context) {
        val screens = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)
        for (action in screens) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (exception: ActivityNotFoundException) {
                // This phone doesn't have that screen; try the next.
            }
        }
    }

    /** Before Android 10, the chosen assistant's component, as "package/class". */
    private const val ASSISTANT_SETTING = "assistant"
}
