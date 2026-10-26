package app.whittle.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
import app.whittle.design.components.TopBarAction
import app.whittle.design.components.TopBarActionGroup
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.TopBarGroupAction
import app.whittle.design.components.connectedShape
import app.whittle.design.tokens.WhittleColors
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** How far a double tap zooms in, and the most a pinch can. */
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 5f

/**
 * A message's photos, full screen and always dark, starting at [start]: pinch or double-tap to zoom, swipe to the
 * next. Share sends the photo to another app; Show in files opens it in the workspace.
 */
@Composable
fun PhotoViewer(
    photos: List<AttachmentEntry>,
    start: AttachmentEntry,
    maxSidePx: Int,
    /** Opens the photo in the workspace; null where the file browser isn't here. */
    onShowInFiles: ((AttachmentEntry) -> Unit)?,
    /** Sends the photo to another app; null where there's none to send to. */
    onShare: ((AttachmentEntry) -> Unit)?,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = fullScreenDialog()) {
        // A photo reads best on black, whatever the app's theme.
        MaterialTheme(colorScheme = WhittleColors.Dark, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            val colors = MaterialTheme.colorScheme
            val pager = rememberPagerState(initialPage = photos.indexOf(start).coerceAtLeast(0)) { photos.size }
            var zoomed by remember { mutableStateOf(false) }
            Box(Modifier.fillMaxSize().background(colors.scrim)) {
                HorizontalPager(pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                    ZoomablePhoto(photos[page], maxSidePx, onZoomed = { if (page == pager.currentPage) zoomed = it })
                }
                val current = photos[pager.currentPage]
                // The bar fades into the photo, so its words stay readable over a zoomed-in picture.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(colors.scrim, colors.scrim.copy(alpha = 0f))))
                        .safeDrawingPadding()
                        .padding(start = WhittleSpace.m, end = WhittleSpace.m, top = WhittleSpace.s, bottom = WhittleSpace.xxl),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
                ) {
                    TopBarButton(icon = "close", contentDescription = stringResource(Res.string.attachment_close), onClick = onDismiss)
                    Column(Modifier.weight(1f)) {
                        Text(current.name, style = MaterialTheme.typography.titleMedium, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(Res.string.attachment_position, pager.currentPage + 1, photos.size, sizeText(current.sizeBytes)),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    val actions = listOfNotNull(
                        onShare?.let { share -> Triple("share", Res.string.files_share) { share(current) } },
                        onShowInFiles?.let { show -> Triple("folder_open", Res.string.attachment_show_in_files) { show(current) } },
                    )
                    if (actions.isNotEmpty()) {
                        TopBarActionGroup {
                            actions.forEachIndexed { index, (icon, label, onClick) ->
                                TopBarAction(
                                    icon = icon,
                                    contentDescription = stringResource(label),
                                    onClick = onClick,
                                    shape = connectedShape(index, actions.size),
                                    modifier = TopBarGroupAction,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(photo: AttachmentEntry, maxSidePx: Int, onZoomed: (Boolean) -> Unit) {
    val images = LocalWorkspaceImages.current
    val bitmap by produceState<ImageBitmap?>(null, photo.path) { value = images.load(photo.path, maxSidePx) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val bounds = with(LocalDensity.current) { Offset(maxWidth.toPx(), maxHeight.toPx()) }
        // Panning stops at the photo's edges, so it never slides off the screen.
        fun clamp(value: Offset, zoom: Float): Offset {
            val limit = bounds * ((zoom - 1f) / 2f)
            return Offset(value.x.coerceIn(-limit.x, limit.x), value.y.coerceIn(-limit.y, limit.y))
        }
        val transform = rememberTransformableState { zoomBy, panBy, _ ->
            scale = (scale * zoomBy).coerceIn(1f, MAX_ZOOM)
            offset = clamp(offset + panBy, scale)
            onZoomed(scale > 1f)
        }
        val shown = bitmap
        if (shown == null) {
            CircularProgressIndicator(Modifier.padding(WhittleSpace.l).size(WhittleSize.Icon.xl * 2))
            return@BoxWithConstraints
        }
        Image(
            bitmap = shown,
            contentDescription = photo.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(photo.path) {
                    detectTapGestures(onDoubleTap = { tap ->
                        val zoomIn = scale == 1f
                        scale = if (zoomIn) DOUBLE_TAP_ZOOM else 1f
                        // Zooms in toward where the user tapped.
                        offset = if (zoomIn) clamp((Offset(size.width / 2f, size.height / 2f) - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM) else Offset.Zero
                        onZoomed(zoomIn)
                    })
                }
                // While it fills the screen, a one-finger swipe turns the page instead of panning.
                .transformable(transform, canPan = { scale > 1f })
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
