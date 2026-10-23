package {{package}}

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        super.onCreate(savedInstanceState)
        val title = TextView(this).apply {
            text = applicationInfo.loadLabel(packageManager)
            textSize = 24f
            gravity = Gravity.CENTER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Android 15 and later draw apps edge to edge: this keeps the content clear of the status and navigation bars.
            fitsSystemWindows = true
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
            addView(title)
        }
        setContentView(root)
    }

    /** Sizes are in dp, so they look the same on every screen; text sizes are already in sp. */
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
