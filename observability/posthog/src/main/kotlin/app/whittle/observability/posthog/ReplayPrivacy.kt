package app.whittle.observability.posthog

import androidx.compose.ui.Modifier
import com.posthog.android.replay.PostHogMaskModifier

/**
 * What session recordings may show. Whittle's own interface (buttons, labels, settings, the beaver) is
 * recorded as it is; what the user writes or makes is not, except in a developer's own test build.
 */
object ReplayPrivacy {
    /** Set once at start-up from the build: true only for a developer's own test builds. */
    @Volatile
    var showUserContent: Boolean = false
        internal set
}

/** Keeps an element out of session recordings whatever the build, for secrets such as API keys. */
fun Modifier.hiddenFromReplay(): Modifier = with(PostHogMaskModifier) { this@hiddenFromReplay.postHogMask() }

/** The user's content (messages, answers, steps, titles, apps): masked in recordings unless the build shows content. */
fun Modifier.userContent(): Modifier = if (ReplayPrivacy.showUserContent) this else hiddenFromReplay()
