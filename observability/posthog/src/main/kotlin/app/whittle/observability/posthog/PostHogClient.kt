package app.whittle.observability.posthog

import android.content.Context
import app.whittle.core.config.ConsentLevel
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
        /**
         * [projectKey] is PostHog's public project key, supplied by the build, not by config files.
         * Session replay also needs "Record user sessions" turned on in the PostHog project's settings.
         */
        fun install(context: Context, projectKey: String, config: TelemetryConfig) {
            val replay = config.sessionReplay
            val posthog = PostHogAndroidConfig(apiKey = projectKey, host = config.posthogHost).apply {
                sessionReplay = replay.enabled && config.defaultConsent != ConsentLevel.OFF
                sessionReplayConfig.screenshot = replay.screenshotMode
                sessionReplayConfig.maskAllTextInputs = replay.maskAllText
                sessionReplayConfig.maskAllImages = replay.maskAllImages
                sessionReplayConfig.captureTouches = replay.captureTouches
                sessionReplayConfig.captureLogcat = replay.captureLogcat
                sessionReplayConfig.sampleRate = replay.sampleRate
            }
            PostHogAndroid.setup(context, posthog)
        }
    }
}
