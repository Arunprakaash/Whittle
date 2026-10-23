package {{package}}

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import java.io.PrintWriter
import java.io.StringWriter

/** Sends an uncaught crash to Whittle so it can offer to fix it; never throws from the handler. */
object CrashReporter {
    private var installed = false

    /** Wraps the handler once per process, however often an activity is created. */
    @Synchronized
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                // Not Log.getStackTraceString: it returns "" when the chain holds an UnknownHostException.
                val stack = StringWriter()
                PrintWriter(stack).use { error.printStackTrace(it) }
                val values = ContentValues().apply {
                    put("stack", stack.toString())
                    put("package", app.packageName)
                }
                app.contentResolver.insert(Uri.parse("content://{{crashAuthority}}/crashes"), values)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
