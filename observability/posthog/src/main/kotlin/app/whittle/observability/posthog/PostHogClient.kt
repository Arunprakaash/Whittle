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
         * [showContent] records text and images as they are, for a developer's own test builds; elements
         * marked [hiddenFromReplay] and password fields stay masked regardless.
         */
        fun install(context: Context, projectKey: String, config: TelemetryConfig, showContent: Boolean, debugLogs: Boolean) {
            val replay = config.sessionReplay
            val posthog = PostHogAndroidConfig(apiKey = projectKey, host = config.posthogHost).apply {
                debug = debugLogs
                // Nothing is sent until the user's saved choices are known; see applyChoices.
                optOut = true
                sessionReplay = replay.enabled && config.defaultConsent != ConsentLevel.OFF
                sessionReplayConfig.screenshot = replay.screenshotMode
                sessionReplayConfig.maskAllTextInputs = replay.maskAllText && !showContent
                sessionReplayConfig.maskAllImages = replay.maskAllImages && !showContent
                sessionReplayConfig.captureTouches = replay.captureTouches
                sessionReplayConfig.captureLogcat = replay.captureLogcat
                sessionReplayConfig.sampleRate = replay.sampleRate
            }
            PostHogAndroid.setup(context, posthog)
        }

        /** Applies the user's Settings choices as they change: stop everything, or send, with or without recordings. */
        fun applyChoices(shareUsageData: Boolean, screenRecordings: Boolean) {
            if (!shareUsageData) {
                PostHog.stopSessionReplay()
                PostHog.optOut()
                return
            }
            PostHog.optIn()
            if (screenRecordings) PostHog.startSessionReplay(resumeCurrent = true) else PostHog.stopSessionReplay()
        }
    }
}
