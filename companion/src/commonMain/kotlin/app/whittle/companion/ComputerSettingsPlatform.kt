package app.whittle.companion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import app.whittle.companion.resources.Res
import app.whittle.companion.resources.settings_on_phone
import app.whittle.connections.ui.ConnectionsHost
import app.whittle.connections.ui.ConnectionsPlatform
import app.whittle.connections.ui.ConnectionsSection
import app.whittle.design.components.SupportingNote
import app.whittle.design.tokens.WhittleSpace
import app.whittle.settings.ui.SettingsPlatform
import org.jetbrains.compose.resources.stringResource

/**
 * What a computer does for Settings: they're the phone's, changed over the link. Connections show here; phone
 * features, Android's prompts and roles, and installing an update stay on the phone, and the page says so.
 */
class ComputerSettingsPlatform(
    private val connections: ConnectionsHost,
    private val connectionsPlatform: ConnectionsPlatform,
    private val onBrowseConnections: () -> Unit,
    private val onConnectionTools: (connectionId: String) -> Unit,
) : SettingsPlatform {
    @Composable
    override fun PhoneSections() {
        // Spaced as the page spaces its own sections.
        Column(verticalArrangement = Arrangement.spacedBy(WhittleSpace.xl)) {
            ConnectionsSection(connections, connectionsPlatform, onBrowse = onBrowseConnections, onChooseTools = onConnectionTools)
            SupportingNote("mobile_screen_share", stringResource(Res.string.settings_on_phone))
        }
    }

    override val requestNotifications: (() -> Unit)? = null

    @Composable
    override fun assistantHeld(): Boolean? = null

    override fun chooseAssistant() = Unit

    override val installUpdate: (() -> Unit)? = null

    @Composable
    override fun UpdateEffects() = Unit
}
