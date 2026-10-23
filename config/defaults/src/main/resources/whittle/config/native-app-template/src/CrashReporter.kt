package {{package}}

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.util.Log

/** Sends an uncaught crash to Whittle so it can offer to fix it; never throws from the handler. */
object CrashReporter {
    fun install(context: Context) {
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val values = ContentValues().apply {
                    put("stack", Log.getStackTraceString(error))
                    put("package", app.packageName)
                }
                app.contentResolver.insert(Uri.parse("content://{{crashAuthority}}/crashes"), values)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
