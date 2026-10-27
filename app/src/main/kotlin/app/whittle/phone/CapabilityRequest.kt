package app.whittle.phone

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.whittle.tools.android.AndroidCapabilities
import app.whittle.tools.android.HealthAvailability
import app.whittle.tools.android.HealthConnect
import app.whittle.tools.api.CapabilityId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Whether [capability] is granted: by Android for its runtime permissions, by Health Connect for health data. */
suspend fun isGranted(context: Context, health: HealthConnect, capability: CapabilityId): Boolean =
    if (capability in AndroidCapabilities.HEALTH_CAPABILITIES) health.isGranted(capability) else AndroidCapabilities.isGranted(context, capability)

/**
 * Asks for whichever of the given capabilities aren't granted yet: Android's prompt for runtime permissions, then Health
 * Connect's own screen for health data, or Play where Health Connect still has to be installed (Android 9 to 13), then
 * Android's settings screen for what no prompt grants (notification access, location all the time from Android 11).
 * [onDone] gets whether all of them are granted afterwards.
 */
@Composable
fun rememberCapabilityRequest(health: HealthConnect, onDone: (granted: Boolean) -> Unit): (List<CapabilityId>) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val done by rememberUpdatedState(onDone)
    // Each launcher's answer, awaited by the request that launched it.
    val answers = remember { Answers() }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers.runtime?.complete(Unit) }
    val healthScreen = rememberLauncherForActivityResult(health.requestContract()) { answers.health?.complete(Unit) }
    val settingsScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { answers.settings?.complete(Unit) }
    return { capabilities ->
        scope.launch {
            val missing = capabilities.filterNot { isGranted(context, health, it) }
            val permissions = missing.mapNotNull(AndroidCapabilities::permissionFor)
            if (permissions.isNotEmpty()) {
                CompletableDeferred<Unit>().also { answers.runtime = it; runtime.launch(permissions.toTypedArray()) }.await()
            }
            val healthMissing = missing.filter { it in AndroidCapabilities.HEALTH_CAPABILITIES }
            if (healthMissing.isNotEmpty()) {
                when (health.availability()) {
                    HealthAvailability.READY -> {
                        val ask = healthMissing.flatMapTo(mutableSetOf()) { health.askable(it) } - health.granted()
                        if (ask.isNotEmpty()) CompletableDeferred<Unit>().also { answers.health = it; healthScreen.launch(ask) }.await()
                    }
                    HealthAvailability.NEEDS_APP -> try {
                        context.startActivity(health.installIntent())
                    } catch (_: ActivityNotFoundException) {
                        // No Play on this phone; the Health row still says it needs Health Connect.
                    }
                    HealthAvailability.UNSUPPORTED -> Unit
                }
            }
            // One screen at a time, each only if the prompts above left it missing; the user comes back from each.
            for (capability in missing.filterNot { isGranted(context, health, it) }) {
                val intent = AndroidCapabilities.settingsIntentFor(context, capability) ?: continue
                try {
                    CompletableDeferred<Unit>().also { answers.settings = it; settingsScreen.launch(intent) }.await()
                } catch (_: ActivityNotFoundException) {
                    // A phone without that screen; the capability stays missing and its row says so.
                }
            }
            done(capabilities.all { isGranted(context, health, it) })
        }
    }
}

private class Answers {
    var runtime: CompletableDeferred<Unit>? = null
    var health: CompletableDeferred<Unit>? = null
    var settings: CompletableDeferred<Unit>? = null
}
