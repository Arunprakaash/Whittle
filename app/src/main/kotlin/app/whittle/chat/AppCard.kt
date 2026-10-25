package app.whittle.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.agent.PastedKind
import app.whittle.R
import app.whittle.apps.InstallResult
import app.whittle.apps.PowerfulDeclaration
import app.whittle.design.components.MenuAction
import app.whittle.design.components.WhittleIcon
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.tokens.WhittleTextStyles

/**
 * An app Whittle built: Install, Update, Open or Remove it. Only the user's tap installs anything, and what is installed is
 * the card's own build, checked again right before it goes to Android.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppCard(item: ThreadItem.App, viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val states by viewModel.appStates.collectAsStateWithLifecycle()
    val shown = states[item.key] ?: AppUiState()
    var notice by remember { mutableStateOf<Int?>(null) }
    var needsAllow by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<List<PowerfulDeclaration>>(emptyList()) }
    var showingPowerful by remember { mutableStateOf(false) }
    // An uninstall from the launcher, or an install finished in Android's own screen, shows here as soon as the user is back.
    LaunchedEffect(item.key) { viewModel.refreshApp(item) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshApp(item) }
    val state = appCardState(item, shown.installed)
    val installing = shown.installing
    val result = shown.result
    val colors = MaterialTheme.colorScheme
    val crashes by viewModel.appCrashes.collectAsStateWithLifecycle()
    val crash = crashes[item.packageName]
    var crashExpanded by remember { mutableStateOf(false) }
    val fixFolder = viewModel.fixFolder(item)
    val askReady by viewModel.askToFixReady.collectAsStateWithLifecycle()
    val fixRequest = stringResource(R.string.app_fix_message, viewModel.fixName(item.name), fixFolder.orEmpty())

    fun begin() {
        notice = null
        needsAllow = false
        viewModel.beginInstall(
            item,
            needsAllow = {
                needsAllow = true
                try {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    notice = R.string.app_card_settings_failed
                }
            },
            confirm = { confirming = it },
        )
    }

    Column(
        modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.xl)).padding(WhittleSpace.m),
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
            Box(Modifier.size(WhittleSize.Tile.m).background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.m)), contentAlignment = Alignment.Center) {
                WhittleIcon("extension", size = WhittleSize.Icon.l, tint = colors.onSecondaryContainer)
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        R.string.app_card_version_status,
                        item.versionCode,
                        stringResource(
                            when (state) {
                                AppCardState.INSTALL -> R.string.app_card_not_installed
                                AppCardState.UPDATE -> R.string.app_card_update_available
                                AppCardState.OPEN -> R.string.app_card_installed
                                AppCardState.NEWER_INSTALLED -> R.string.app_card_newer_installed
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Rarer actions, so the card has one main button.
            CardMenu(
                stringResource(R.string.app_card_more),
                buildList {
                    if (state != AppCardState.INSTALL) {
                        add(MenuAction(stringResource(R.string.app_card_remove), "delete", { viewModel.clearAppResult(item); needsAllow = false; notice = if (remove(context, item)) null else R.string.app_card_remove_failed }))
                    }
                    if (crash != null && fixFolder != null) {
                        add(MenuAction(stringResource(R.string.app_card_ask_fix), "build", { viewModel.askToFix(fixRequest, PastedKind.APP_CRASH, item.name, crash) }, enabled = askReady))
                    }
                },
            )
        }
        // One line for what it can do that matters; the list opens on tap (the install dialog lists it too).
        if (item.powerful.isNotEmpty()) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(colors.errorContainer)
                    .clickable(role = Role.Button) { showingPowerful = true }
                    .padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
            ) {
                WhittleIcon("shield", size = WhittleSize.Icon.s, tint = colors.onErrorContainer)
                Text(pluralStringResource(R.plurals.app_card_powerful_title, item.powerful.size, item.powerful.size), style = MaterialTheme.typography.labelLarge, color = colors.onErrorContainer)
            }
        }
        val message = when {
            installing -> R.string.app_card_installing
            notice != null -> notice
            needsAllow -> R.string.app_card_allow_installs
            else -> result?.let(::resultText)
        }
        val neutral = result == null || result == InstallResult.INSTALLED || result == InstallResult.CANCELLED
        val failed = !installing && (notice != null || (!needsAllow && !neutral))
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = if (failed) colors.error else colors.onSurfaceVariant) }
        if (crash != null) {
            Column(
                Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, RoundedCornerShape(WhittleRadius.l)).padding(WhittleSpace.m),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
            ) {
                Text(stringResource(R.string.app_card_crash_title), style = MaterialTheme.typography.labelLarge)
                Text(
                    crash,
                    style = WhittleTextStyles.mono,
                    color = colors.onSurfaceVariant,
                    maxLines = if (crashExpanded) Int.MAX_VALUE else CRASH_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { crashExpanded = !crashExpanded }) {
                    Text(stringResource(if (crashExpanded) R.string.app_card_crash_less else R.string.app_card_crash_more))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            when (state) {
                AppCardState.INSTALL -> Action("download", R.string.app_card_install, primary = true, enabled = !installing) { begin() }
                AppCardState.UPDATE -> Action("download", R.string.app_card_update, primary = true, enabled = !installing) { begin() }
                AppCardState.OPEN, AppCardState.NEWER_INSTALLED -> {
                    Action("open_in_new", R.string.app_card_open, primary = true) {
                        viewModel.clearAppResult(item)
                        needsAllow = false
                        val launch = context.packageManager.getLaunchIntentForPackage(item.packageName)
                        notice = if (launch == null) R.string.app_card_open_failed else null
                        if (launch != null) {
                            try {
                                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: ActivityNotFoundException) {
                                notice = R.string.app_card_open_failed
                            }
                        }
                    }
                }
            }
        }
    }
    if (showingPowerful) PowerfulList(item, onDismiss = { showingPowerful = false })
    if (confirming.isNotEmpty()) PowerfulDialog(item, confirming, onContinue = { confirming = emptyList(); viewModel.installApp(item) }, onCancel = { confirming = emptyList() })
}

private const val CRASH_LINES = 4

private fun resultText(result: InstallResult): Int = when (result) {
    InstallResult.INSTALLED -> R.string.app_card_installed_done
    InstallResult.CANCELLED -> R.string.app_card_cancelled
    InstallResult.KEY_CHANGED -> R.string.app_card_key_changed
    InstallResult.BLOCKED -> R.string.app_card_blocked
    InstallResult.FAILED -> R.string.app_card_failed
    InstallResult.CHANGED -> R.string.app_card_changed
}

private fun remove(context: android.content.Context, item: ThreadItem.App): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Whittle's own words about what the app could do, before Android's install prompt. */
@Composable
private fun PowerfulDialog(item: ThreadItem.App, powerful: List<PowerfulDeclaration>, onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.app_powerful_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                Text(pluralStringResource(R.plurals.app_powerful_dialog_body, powerful.size, item.name, powerful.size))
                powerful.forEach { Text(stringResource(R.string.app_powerful_dialog_item, it.sentence), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(R.string.app_powerful_dialog_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.app_powerful_dialog_cancel)) } },
    )
}

/** What the app's powerful permissions let it do, opened from the card's one-line summary. */
@Composable
private fun PowerfulList(item: ThreadItem.App, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.app_card_powerful_title, item.powerful.size, item.powerful.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                item.powerful.forEach { Text(stringResource(R.string.app_powerful_dialog_item, it.sentence), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_card_powerful_close)) } },
    )
}

@Composable
private fun Action(icon: String, label: Int, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        WhittleIcon(icon, size = WhittleSize.Icon.m)
        Text(stringResource(label), modifier = Modifier.padding(start = WhittleSpace.s))
    }
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.height(WhittleSize.Button.small)) { content() }
    } else {
        FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.height(WhittleSize.Button.small)) { content() }
    }
}
