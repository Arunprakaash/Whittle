package app.whittle.artifacts

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.R
import app.whittle.design.components.OpenInAction
import app.whittle.design.components.OpenInRow
import app.whittle.design.components.TopBarAction
import app.whittle.design.components.TopBarActionGroup
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.TopBarGroupAction
import app.whittle.design.components.connectedShape
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import kotlinx.coroutines.launch

/** Tapping an artifact card: the app runs in a tall sheet over the chat, with ways to open it elsewhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactSheet(
    ref: ArtifactRef,
    artifacts: Artifacts,
    revision: Int,
    heightFraction: Float,
    onDismiss: () -> Unit,
    onFullScreen: () -> Unit,
) {
    var manualReloads by remember { mutableIntStateOf(0) }
    val elsewhere = rememberOpenElsewhere(ref, artifacts)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(heightFraction).padding(horizontal = WhittleSpace.l).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m)) {
                Text(ref.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TopBarAction(
                    icon = "refresh",
                    contentDescription = stringResource(R.string.artifact_reload),
                    onClick = { manualReloads += 1 },
                    shape = CircleShape,
                    modifier = Modifier.size(WhittleSize.Tile.l),
                )
            }
            ArtifactContent(ref, artifacts, revision + manualReloads, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(WhittleRadius.l)))
            OpenInRow(
                listOf(
                    OpenInAction("fullscreen", stringResource(R.string.artifact_full_screen), onFullScreen),
                    OpenInAction("open_in_new", stringResource(R.string.artifact_browser), elsewhere.browser),
                    OpenInAction("share", stringResource(R.string.artifact_share), elsewhere.share),
                ),
                modifier = Modifier.padding(bottom = WhittleSpace.m),
            )
        }
    }
}

/** The app on its own screen, with the same top bar language as the chat. */
@Composable
fun ArtifactScreen(ref: ArtifactRef, artifacts: Artifacts, revision: Int, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var manualReloads by remember { mutableIntStateOf(0) }
    val elsewhere = rememberOpenElsewhere(ref, artifacts)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
            ) {
                TopBarButton("arrow_back", stringResource(R.string.artifact_back), onBack)
                Text(ref.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TopBarActionGroup {
                    val actions = listOf<Triple<String, String, () -> Unit>>(
                        Triple("refresh", stringResource(R.string.artifact_reload)) { manualReloads += 1 },
                        Triple("open_in_new", stringResource(R.string.artifact_browser), elsewhere.browser),
                        Triple("share", stringResource(R.string.artifact_share), elsewhere.share),
                    )
                    actions.forEachIndexed { index, (icon, label, onClick) ->
                        TopBarAction(icon, label, onClick, shape = connectedShape(index, actions.size), modifier = TopBarGroupAction)
                    }
                }
            }
        },
    ) { padding ->
        ArtifactContent(ref, artifacts, revision + manualReloads, Modifier.padding(padding).fillMaxSize())
    }
}

@Composable
private fun ArtifactContent(ref: ArtifactRef, artifacts: Artifacts, revision: Int, modifier: Modifier) {
    if (artifacts.exists(ref)) {
        ArtifactWebView(ref, artifacts, revision, modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.artifact_missing), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private class OpenElsewhere(val browser: () -> Unit, val share: () -> Unit)

/** Browser and share both hand over the app bundled into one file; a toast says when nothing can take it. */
@Composable
private fun rememberOpenElsewhere(ref: ArtifactRef, artifacts: Artifacts): OpenElsewhere {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.artifact_no_browser)
    val noShare = stringResource(R.string.artifact_no_share)
    return remember(ref) {
        OpenElsewhere(
            browser = { scope.launch { if (!artifacts.openInBrowser(ref)) Toast.makeText(context, noBrowser, Toast.LENGTH_SHORT).show() } },
            share = { scope.launch { if (!artifacts.share(ref)) Toast.makeText(context, noShare, Toast.LENGTH_SHORT).show() } },
        )
    }
}
