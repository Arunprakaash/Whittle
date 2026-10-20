package app.whittle.observability.posthog

import androidx.compose.ui.Modifier
import com.posthog.android.replay.PostHogMaskModifier

/** Keeps an element out of session recordings whatever the masking settings, for secrets such as API keys. */
fun Modifier.hiddenFromReplay(): Modifier = with(PostHogMaskModifier) { this@hiddenFromReplay.postHogMask() }
