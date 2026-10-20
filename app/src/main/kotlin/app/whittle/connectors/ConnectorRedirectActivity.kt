package app.whittle.connectors

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.whittle.MainActivity
import app.whittle.WhittleApplication

/**
 * Where a connector's sign-in page sends the user back (app.whittle://oauth/…). Hands the address to the
 * connector, then returns to Whittle, closing the browser tab that sat above it.
 */
class ConnectorRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.toString()?.let { (application as WhittleApplication).graph.connectors.complete(it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
