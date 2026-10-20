package app.whittle.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.whittle.R
import app.whittle.artifacts.ArtifactRef
import app.whittle.design.components.SettingsRow
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.segmentedShape
import app.whittle.design.tokens.WhittleSpace

/** Every app the agent made, across all chats. Tapping one opens it. */
@Composable
fun ArtifactsScreen(
    artifacts: List<ArtifactListItem>,
    onBack: () -> Unit,
    onOpen: (ArtifactRef) -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("arrow_back", stringResource(R.string.settings_back), onBack)
                Text(stringResource(R.string.artifacts_title), style = MaterialTheme.typography.titleLarge)
            }
        },
    ) { padding ->
        if (artifacts.isEmpty()) {
            Text(
                stringResource(R.string.artifacts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(WhittleSpace.xl),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = WhittleSpace.l, vertical = WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            itemsIndexed(artifacts, key = { _, item -> item.ref.workspace + "/" + item.ref.path }) { index, item ->
                SettingsRow(
                    icon = "web_asset",
                    title = item.ref.title,
                    subtitle = null,
                    shape = segmentedShape(index, artifacts.size),
                    onClick = { onOpen(item.ref) },
                )
            }
        }
    }
}
