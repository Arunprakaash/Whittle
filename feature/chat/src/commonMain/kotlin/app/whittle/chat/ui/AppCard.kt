package app.whittle.chat.ui

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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
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
fun AppCard(
    item: ThreadRow.App,
    askReady: Boolean,
    onAction: (ChatAction) -> Unit,
    /** The device's installer; null where apps can't be installed, and then the card says they're on the phone. */
    installs: AppInstalls?,
    modifier: Modifier = Modifier,
) {
    var notice by remember { mutableStateOf<StringResource?>(null) }
    var needsAllow by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<List<PowerRow>>(emptyList()) }
    var showingPowerful by remember { mutableStateOf(false) }
    // An uninstall from the launcher, or an install finished in Android's own screen, shows here as soon as the user is back.
    LaunchedEffect(item.key) { onAction(ChatAction.RefreshApp(item.key)) }
    installs?.RefreshOnResume(item.key)
    val state = appCardState(item.versionCode, item.installed)
    val installing = item.installing
    val result = item.result
    val colors = MaterialTheme.colorScheme
    val crash = item.crash
    var crashExpanded by remember { mutableStateOf(false) }
    val fixFolder = item.fixFolder
    val fixRequest = stringResource(Res.string.app_fix_message, item.fixName, fixFolder.orEmpty())

    fun begin() {
        notice = null
        needsAllow = false
        installs?.begin(
            item.key,
            needsAllow = {
                needsAllow = true
                if (!installs.allowInstalls()) notice = Res.string.app_card_settings_failed
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
                        Res.string.app_card_version_status,
                        item.versionCode,
                        stringResource(
                            when (state) {
                                AppCardState.INSTALL -> Res.string.app_card_not_installed
                                AppCardState.UPDATE -> Res.string.app_card_update_available
                                AppCardState.OPEN -> Res.string.app_card_installed
                                AppCardState.NEWER_INSTALLED -> Res.string.app_card_newer_installed
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
                stringResource(Res.string.app_card_more),
                buildList {
                    if (state != AppCardState.INSTALL && installs != null) {
                        add(MenuAction(stringResource(Res.string.app_card_remove), "delete", { onAction(ChatAction.ClearAppResult(item.key)); needsAllow = false; notice = if (installs.remove(item.key)) null else Res.string.app_card_remove_failed }))
                    }
                    if (crash != null && fixFolder != null) {
                        add(MenuAction(stringResource(Res.string.app_card_ask_fix), "build", { onAction(ChatAction.FixApp(item.key, fixRequest)) }, enabled = askReady))
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
                Text(pluralStringResource(Res.plurals.app_card_powerful_title, item.powerful.size, item.powerful.size), style = MaterialTheme.typography.labelLarge, color = colors.onErrorContainer)
            }
        }
        val message = when {
            installing -> Res.string.app_card_installing
            notice != null -> notice
            needsAllow -> Res.string.app_card_allow_installs
            else -> result?.let(::resultText)
        }
        val neutral = result == null || result == InstallOutcome.Installed || result == InstallOutcome.Cancelled
        val failed = !installing && (notice != null || (!needsAllow && !neutral))
        message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = if (failed) colors.error else colors.onSurfaceVariant) }
        if (crash != null) {
            Column(
                Modifier.fillMaxWidth().background(colors.surfaceContainerHigh, RoundedCornerShape(WhittleRadius.l)).padding(WhittleSpace.m),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs),
            ) {
                Text(stringResource(Res.string.app_card_crash_title), style = MaterialTheme.typography.labelLarge)
                Text(
                    crash,
                    style = WhittleTextStyles.mono,
                    color = colors.onSurfaceVariant,
                    maxLines = if (crashExpanded) Int.MAX_VALUE else CRASH_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { crashExpanded = !crashExpanded }) {
                    Text(stringResource(if (crashExpanded) Res.string.app_card_crash_less else Res.string.app_card_crash_more))
                }
            }
        }
        if (installs == null) {
            Text(stringResource(Res.string.app_card_on_phone), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else FlowRow(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
            when (state) {
                AppCardState.INSTALL -> Action("download", Res.string.app_card_install, primary = true, enabled = !installing) { begin() }
                AppCardState.UPDATE -> Action("download", Res.string.app_card_update, primary = true, enabled = !installing) { begin() }
                AppCardState.OPEN, AppCardState.NEWER_INSTALLED -> {
                    Action("open_in_new", Res.string.app_card_open, primary = true) {
                        onAction(ChatAction.ClearAppResult(item.key))
                        needsAllow = false
                        notice = if (installs.open(item.key)) null else Res.string.app_card_open_failed
                    }
                }
            }
        }
    }
    if (showingPowerful) PowerfulList(item, onDismiss = { showingPowerful = false })
    if (confirming.isNotEmpty()) PowerfulDialog(item, confirming, onContinue = { confirming = emptyList(); installs?.install(item.key) }, onCancel = { confirming = emptyList() })
}

private const val CRASH_LINES = 4

private fun resultText(result: InstallOutcome): StringResource = when (result) {
    InstallOutcome.Installed -> Res.string.app_card_installed_done
    InstallOutcome.Cancelled -> Res.string.app_card_cancelled
    InstallOutcome.KeyChanged -> Res.string.app_card_key_changed
    InstallOutcome.Blocked -> Res.string.app_card_blocked
    InstallOutcome.Failed -> Res.string.app_card_failed
    InstallOutcome.Changed -> Res.string.app_card_changed
}

/** Which buttons an app card offers, from the version of the app that is installed. */
enum class AppCardState { INSTALL, UPDATE, OPEN, NEWER_INSTALLED }

/** The card always stands for its own build: an installed build that is older gets Update, a newer one is never replaced. */
fun appCardState(versionCode: Int, installed: Int?): AppCardState = when {
    installed == null -> AppCardState.INSTALL
    installed < versionCode -> AppCardState.UPDATE
    installed == versionCode -> AppCardState.OPEN
    else -> AppCardState.NEWER_INSTALLED
}

/** Whittle's own words about what the app could do, before Android's install prompt. */
@Composable
private fun PowerfulDialog(item: ThreadRow.App, powerful: List<PowerRow>, onContinue: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.app_powerful_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                Text(pluralStringResource(Res.plurals.app_powerful_dialog_body, powerful.size, item.name, powerful.size))
                powerful.forEach { Text(stringResource(Res.string.app_powerful_dialog_item, it.sentence), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text(stringResource(Res.string.app_powerful_dialog_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(Res.string.app_powerful_dialog_cancel)) } },
    )
}

/** What the app's powerful permissions let it do, opened from the card's one-line summary. */
@Composable
private fun PowerfulList(item: ThreadRow.App, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(Res.plurals.app_card_powerful_title, item.powerful.size, item.powerful.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                item.powerful.forEach { Text(stringResource(Res.string.app_powerful_dialog_item, it.sentence), style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.app_card_powerful_close)) } },
    )
}

@Composable
private fun Action(icon: String, label: StringResource, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
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
