package app.whittle.companion

import androidx.compose.runtime.Composable
import app.whittle.companion.resources.Res
import app.whittle.companion.resources.settings_on_phone
import app.whittle.design.components.SupportingNote
import app.whittle.settings.ui.SettingsPlatform
import org.jetbrains.compose.resources.stringResource

/**
 * What a computer does for Settings: they're the phone's, changed over the link. Connections and phone features,
 * Android's prompts and roles, and installing an update stay on the phone, and the page says so.
 */
class ComputerSettingsPlatform : SettingsPlatform {
    @Composable
    override fun PhoneSections() = SupportingNote("mobile_screen_share", stringResource(Res.string.settings_on_phone))

    override val requestNotifications: (() -> Unit)? = null

    @Composable
    override fun assistantHeld(): Boolean? = null

    override fun chooseAssistant() = Unit

    override val installUpdate: (() -> Unit)? = null

    @Composable
    override fun UpdateEffects() = Unit
}
