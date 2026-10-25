package app.whittle.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
 * Asks for whichever of the given capabilities aren't granted yet: Android's prompt for runtime permissions, then the
 * all files access page for storage where Android grants it there, then Health Connect's own screen for health data,
 * or Play where Health Connect still has to be installed (Android 9 to 13).
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
    val settingsPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { answers.settings?.complete(Unit) }
    val healthScreen = rememberLauncherForActivityResult(health.requestContract()) { answers.health?.complete(Unit) }
    return { capabilities ->
        scope.launch {
            val missing = capabilities.filterNot { isGranted(context, health, it) }
            val permissions = missing.flatMap { AndroidCapabilities.permissionsFor(it) }
            if (permissions.isNotEmpty()) {
                CompletableDeferred<Unit>().also { answers.runtime = it; runtime.launch(permissions.toTypedArray()) }.await()
            }
            if (missing.any { AndroidCapabilities.needsSettingsPage(it) }) {
                CompletableDeferred<Unit>().also { answers.settings = it; settingsPage.launch(allFilesAccessIntent(context)) }.await()
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
            done(capabilities.all { isGranted(context, health, it) })
        }
    }
}

/** The page for this app's all files access, or the list of every app's when this phone has no per-app page. */
private fun allFilesAccessIntent(context: Context): Intent {
    val perApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
    return if (perApp.resolveActivity(context.packageManager) != null) perApp else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}

private class Answers {
    var runtime: CompletableDeferred<Unit>? = null
    var settings: CompletableDeferred<Unit>? = null
    var health: CompletableDeferred<Unit>? = null
}
