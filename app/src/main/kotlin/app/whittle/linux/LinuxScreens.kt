package app.whittle.linux

import android.content.Context
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.whittle.design.tokens.WhittleFonts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SkeletonBlock
import app.whittle.design.components.StepGroup
import app.whittle.design.components.StepStatus
import app.whittle.design.components.StepUi
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.apps.ToolchainFailure
import app.whittle.apps.ToolchainState
import app.whittle.onboarding.ONBOARDING_STEPS
import app.whittle.onboarding.OnboardingSteps
import kotlinx.coroutines.launch

/**
 * First run, before the model: offers Linux once. Set up starts it in the background and moves on, so it installs
 * while the user connects a model; Not now moves on without it. [step] is first run's step bar; null after an update.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinuxSetupScreen(graph: AppGraph, step: Int?) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val state by graph.linux.state.collectAsStateWithLifecycle()
    val leave = { graph.appScope.launch { graph.settings.setLinuxOffered() }; Unit }
    // Already set up (from Settings, say): there's nothing to offer.
    if (state is LinuxState.Ready) LaunchedEffect(Unit) { graph.settings.setLinuxOffered() }
    Column(
        Modifier.fillMaxSize().background(colors.surface).safeDrawingPadding().padding(horizontal = WhittleSpace.xxl),
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            step?.let { current -> OnboardingSteps(current, ONBOARDING_STEPS, Modifier.padding(top = WhittleSpace.l, bottom = WhittleSpace.xxl)) }
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl)) {
                Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                    Text(stringResource(R.string.linux_onboarding_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.linux_onboarding_body), style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                Column(
                    Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl)).padding(WhittleSpace.l),
                    verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
                ) {
                    GETS.forEach { (icon, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                            WhittleIcon(icon, size = WhittleSize.Icon.l, tint = colors.primary)
                            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                (state as? LinuxState.Failed)?.let { failed ->
                    Text(stringResource(R.string.linux_failed_reason, failed.reason), style = MaterialTheme.typography.bodyMedium, color = colors.error)
                }
                SizeFacts(graph.linux.image)
                Row(Modifier.padding(horizontal = WhittleSpace.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    WhittleIcon("wifi", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant)
                    Text(stringResource(R.string.linux_wifi_tip), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.padding(top = WhittleSpace.l, bottom = WhittleSpace.xl), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
            if (state is LinuxState.SettingUp) {
                Button(onClick = leave, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.large)) {
                    Text(stringResource(R.string.linux_continue), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = { LinuxSetupService.setUp(context); leave() },
                    shapes = ButtonDefaults.shapes(),
                    enabled = graph.linux.image != null,
                    modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.large),
                ) {
                    WhittleIcon("download", size = WhittleSize.Icon.l)
                    Spacer(Modifier.width(WhittleSpace.s))
                    Text(
                        stringResource(if (state is LinuxState.Failed) R.string.linux_try_again else R.string.linux_onboarding_set_up),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                TextButton(onClick = leave, modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.default)) {
                    Text(stringResource(R.string.linux_skip), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** The download, the space setup takes and the space free, as plain rows with the numbers in mono. */
@Composable
private fun SizeFacts(image: LinuxImage?) {
    val context = LocalContext.current
    val facts = listOf(
        R.string.linux_fact_download to Formatter.formatShortFileSize(context, image?.sizeBytes ?: 0L),
        R.string.linux_fact_start to Formatter.formatShortFileSize(context, image?.setupSizeBytes ?: 0L),
        R.string.linux_fact_free to Formatter.formatShortFileSize(context, freeBytes(context)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        facts.forEachIndexed { index, (label, value) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow, segmentedShape(index, facts.size))
                    .padding(horizontal = WhittleSpace.l, vertical = WhittleSpace.m),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = WhittleFonts.mono), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun freeBytes(context: Context): Long = StatFs(context.filesDir.path).availableBytes

private const val MANAGE_ROWS = 3
/** Keeps a small part visible in the bar. */
private const val MIN_WEIGHT = 1f
private const val ADDED_ALPHA = 0.55f
private const val CACHES_ALPHA = 0.22f
private const val PERCENT = 100f
private val FACT_ICONS = listOf("download", "folder_zip", "folder")
/** What Linux makes possible, with its icons. */
private val GETS = listOf("package_2" to R.string.linux_get_apt, "memory" to R.string.linux_get_compile, "terminal" to R.string.linux_get_run)
private val STEP_ICONS = mapOf(
    LinuxSetupStep.Download to "download",
    LinuxSetupStep.Unpack to "folder_zip",
    LinuxSetupStep.Configure to "shield",
    LinuxSetupStep.BasePackages to "package_2",
)
