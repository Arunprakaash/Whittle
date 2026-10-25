package app.whittle.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.R
import app.whittle.WhittleApplication
import app.whittle.drawBehindSystemBars
import app.whittle.design.components.BottomActionButton
import app.whittle.design.components.ScreenHeading
import app.whittle.design.theme.WhittleAppearance
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.theme.isDark
import app.whittle.design.tokens.WhittleSpace

/**
 * How Whittle uses health data, which Health Connect shows from its permission screen and its app settings. Required
 * for Health Connect to list Whittle at all.
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as WhittleApplication).graph
        setContent {
            val settings by graph.currentSettings.collectAsStateWithLifecycle()
            val appearance = settings?.let { WhittleAppearance(wallpaperColors = it.wallpaperColors, haptics = it.haptics, themeMode = it.themeMode) }
                ?: graph.defaultAppearance
            val dark = appearance.themeMode.isDark()
            DisposableEffect(dark) {
                drawBehindSystemBars(dark)
                onDispose { }
            }
            WhittleTheme(appearance, darkTheme = dark) {
                Scaffold(bottomBar = { BottomActionButton("check", stringResource(R.string.health_privacy_done), ::finish) }) { padding ->
                    Column(
                        Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(WhittleSpace.l),
                        verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
                    ) {
                        ScreenHeading(stringResource(R.string.health_privacy_title), stringResource(R.string.health_privacy_intro))
                        listOf(R.string.health_privacy_reads, R.string.health_privacy_model, R.string.health_privacy_off).forEach {
                            Text(stringResource(it), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
