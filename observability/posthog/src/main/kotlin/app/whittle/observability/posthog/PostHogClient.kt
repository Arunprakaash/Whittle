package app.whittle.observability.posthog

import android.content.Context
import app.whittle.core.config.TelemetryConfig
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

/** The one PostHog call the harness needs, behind an interface so the adapter is unit-testable. */
fun interface PostHogClient {
    fun capture(event: String, properties: Map<String, Any>)
}

/** Sends through the PostHog Android SDK. Call [install] once from Application.onCreate. */
class PostHogAndroidClient : PostHogClient {
    override fun capture(event: String, properties: Map<String, Any>) {
        PostHog.capture(event = event, properties = properties)
    }

    companion object {
        /** [projectKey] is PostHog's public project key, supplied by the build, not by config files. */
        fun install(context: Context, projectKey: String, config: TelemetryConfig) {
            PostHogAndroid.setup(context, PostHogAndroidConfig(apiKey = projectKey, host = config.posthogHost))
        }
    }
}
