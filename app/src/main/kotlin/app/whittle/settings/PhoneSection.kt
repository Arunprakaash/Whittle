package app.whittle.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.design.components.segmentedShape
import app.whittle.tools.android.AndroidCapabilities
import app.whittle.tools.api.CapabilityId
import kotlinx.coroutines.launch

/**
 * What Whittle can use on this phone, each with its own switch. Switching one on asks Android for what it needs first;
 * switching it off stops Whittle using it, while what Android granted stays until the user removes it in Android's
 * settings. A switch shows on only when it's on here and Android has granted what it needs.
 */
@Composable
fun PhoneSection(graph: AppGraph, settings: UserSettings?) {
    val context = LocalContext.current
    // Read again when the user comes back from Android's settings or answers its prompt.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checks++ }
    val reminders by graph.reminders.all.collectAsStateWithLifecycle()
    val switches = graph.phoneActions.switches
    SettingsSection(stringResource(R.string.phone_title), footer = stringResource(R.string.phone_footer)) {
        switches.forEachIndexed { index, switch ->
            val capabilities = switch.capabilities.map(::CapabilityId)
            val granted = remember(checks) { capabilities.all { AndroidCapabilities.isGranted(context, it) } }
            val waiting = reminders?.size ?: 0
            SettingsToggleRow(
                icon = switch.icon,
                title = switch.title,
                // The title says what each is; a line only when there's state to show.
                subtitle = if (switch.id == REMINDERS && waiting > 0) pluralStringResource(R.plurals.phone_reminders_waiting, waiting, waiting) else null,
                checked = granted && switch.id !in settings?.phoneOff.orEmpty(),
                onCheckedChange = { on ->
                    graph.appScope.launch { graph.settings.setPhoneFeature(switch.id, on) }
                    if (on) {
                        val missing = capabilities.filterNot { AndroidCapabilities.isGranted(context, it) }.mapNotNull(AndroidCapabilities::permissionFor)
                        if (missing.isNotEmpty()) launcher.launch(missing.toTypedArray())
                    }
                },
                shape = segmentedShape(index, switches.size),
                enabled = settings != null,
            )
        }
    }
}

/** The switch whose row counts the reminders waiting. */
private const val REMINDERS = "reminders"
