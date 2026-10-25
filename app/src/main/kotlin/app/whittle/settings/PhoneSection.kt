package app.whittle.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.SettingsSection
import app.whittle.design.components.SettingsToggleRow
import app.whittle.design.components.segmentedShape
import app.whittle.phone.isGranted
import app.whittle.phone.rememberCapabilityRequest
import app.whittle.tools.android.AndroidCapabilities
import app.whittle.tools.android.HealthAvailability
import app.whittle.tools.api.CapabilityId
import kotlinx.coroutines.launch

/**
 * What Whittle can use on this phone, each with its own switch. Switching one on asks Android (or, for Health, Health
 * Connect; for storage where Android grants it there, the all files access page) for what it needs first; switching it
 * off stops Whittle using it, while what was granted stays until the user removes it in Android's settings. A switch
 * shows on only when it's on here and what it needs is granted.
 */
@Composable
fun PhoneSection(graph: AppGraph, settings: UserSettings?) {
    val context = LocalContext.current
    val health = graph.healthConnect
    // Read again when the user comes back from Android's settings, Health Connect or Play, or answers a prompt.
    var checks by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { checks++ }
    val request = rememberCapabilityRequest(health) { checks++ }
    val reminders by graph.reminders.all.collectAsStateWithLifecycle()
    val switches = graph.phoneActions.switches
    val granted by produceState(emptySet<String>(), checks) {
        value = switches.filter { switch -> switch.capabilities.all { isGranted(context, health, CapabilityId(it)) } }.mapTo(mutableSetOf()) { it.id }
    }
    val healthState = remember(checks) { health.availability() }
    SettingsSection(stringResource(R.string.phone_title), footer = stringResource(R.string.phone_footer)) {
        switches.forEachIndexed { index, switch ->
            val capabilities = switch.capabilities.map(::CapabilityId)
            val shape = segmentedShape(index, switches.size)
            val needsHealth = capabilities.any { it in AndroidCapabilities.HEALTH_CAPABILITIES }
            if (needsHealth && healthState != HealthAvailability.READY) {
                // Health Connect isn't here yet (an app from Play before Android 14), or can't be.
                val installable = healthState == HealthAvailability.NEEDS_APP
                SettingsRow(
                    icon = switch.icon,
                    title = switch.title,
                    subtitle = stringResource(if (installable) R.string.phone_health_needs_app else R.string.phone_health_unsupported),
                    shape = shape,
                    enabled = installable,
                    trailing = if (installable) {
                        { TextButton(onClick = { request(capabilities) }) { Text(stringResource(R.string.phone_health_get)) } }
                    } else {
                        null
                    },
                )
                return@forEachIndexed
            }
            val waiting = reminders?.size ?: 0
            SettingsToggleRow(
                icon = switch.icon,
                title = switch.title,
                // The title says what each is; a line only when there's state to show.
                subtitle = if (switch.id == REMINDERS && waiting > 0) pluralStringResource(R.plurals.phone_reminders_waiting, waiting, waiting) else null,
                checked = switch.id in granted && switch.id !in settings?.phoneOff.orEmpty(),
                onCheckedChange = { on ->
                    graph.appScope.launch { graph.settings.setPhoneFeature(switch.id, on) }
                    if (on) request(capabilities)
                },
                shape = shape,
                enabled = settings != null,
            )
        }
    }
}

/** The switch whose row counts the reminders waiting. */
private const val REMINDERS = "reminders"
