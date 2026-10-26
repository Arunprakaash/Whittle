package app.whittle.chat.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
import app.whittle.design.components.WhittleIcon
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** A photo in the composer's tray: as large as the composer is tall plus a margin, so it reads at a glance. */
private val TrayPhoto: Dp = WhittleSize.Tile.xl + WhittleSpace.l

/** A photo in a sent message: two tray photos' worth of touch target. */
private val MessagePhoto: Dp = WhittleSize.touchTarget * 2

/** Share of the thread's width a message's attachments may take, as its bubble does. */
private const val MESSAGE_WIDTH_FRACTION = 0.82f

/**
 * What the next message carries, above the composer: photos as thumbnails, other files as chips, each with a
 * remove button, then a spinner for each still being saved. Tapping one opens it.
 */
@Composable
fun AttachmentTray(
    attachments: List<AttachmentEntry>,
    attaching: Int,
    onOpen: (AttachmentEntry) -> Unit,
    onRemove: (AttachmentEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** Marks photos the current model can't see. */
    imagesUnseen: Boolean = false,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { attachment ->
            if (attachment.isImage) {
                TrayPhoto(attachment, imagesUnseen, onOpen = { onOpen(attachment) }, onRemove = { onRemove(attachment) })
            } else {
                FileChip(attachment, sizeText(attachment.sizeBytes), Modifier.height(TrayPhoto), onOpen = { onOpen(attachment) }, onRemove = { onRemove(attachment) })
            }
        }
        repeat(attaching) {
            Box(
                Modifier.size(TrayPhoto).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(WhittleRadius.l)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(WhittleSize.Icon.xl), strokeWidth = WhittleSpace.hairline)
            }
        }
    }
}

@Composable
private fun TrayPhoto(attachment: AttachmentEntry, unseen: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(TrayPhoto)) {
        Thumbnail(attachment, Modifier.size(TrayPhoto), onOpen)
        RemoveBadge(stringResource(Res.string.attachment_remove, attachment.name), onRemove, Modifier.align(Alignment.TopEnd).padding(WhittleSpace.xs))
        if (unseen) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(WhittleSpace.xs).size(WhittleSize.Icon.xl).background(colors.surface, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                WhittleIcon("visibility_off", size = WhittleSize.Icon.s, tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** The photo itself, cropped to a rounded square; a plain tile while it loads or if it can't be read. */
@Composable
private fun Thumbnail(attachment: AttachmentEntry, modifier: Modifier, onOpen: () -> Unit) {
    val images = LocalWorkspaceImages.current
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    val bitmap by produceState<ImageBitmap?>(null, attachment.path) { value = images.load(attachment.path, maxSidePx = null) }
    Box(
        modifier
            .clip(RoundedCornerShape(WhittleRadius.l))
            .background(colors.surfaceContainerHigh)
            .clickable(role = Role.Image, onClickLabel = stringResource(Res.string.attachment_open)) { haptics.perform(WhittleHaptic.Tap); onOpen() }
            .semantics { contentDescription = attachment.name },
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize()) }
            ?: WhittleIcon("image", size = WhittleSize.Icon.l, tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun RemoveBadge(label: String, onRemove: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Box(
        modifier
            .size(WhittleSize.Icon.xl)
            .clip(CircleShape)
            .background(colors.inverseSurface)
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onRemove() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        WhittleIcon("close", size = WhittleSize.Icon.s, tint = colors.inverseOnSurface)
    }
}

/** A file that isn't a photo: its kind, name and [detail]. With [onRemove], it can be taken off the message. */
@Composable
private fun FileChip(
    attachment: AttachmentEntry,
    detail: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(
        modifier
            .widthIn(max = WhittleSize.touchTarget * 5)
            .clip(RoundedCornerShape(WhittleRadius.l))
            .background(colors.surfaceContainerLow)
            .clickable(onClickLabel = stringResource(Res.string.attachment_open)) { haptics.perform(WhittleHaptic.Tap); onOpen() }
            .padding(start = WhittleSpace.s, end = if (onRemove != null) WhittleSpace.xs else WhittleSpace.m, top = WhittleSpace.s, bottom = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(
            Modifier.size(WhittleSize.Tile.m).background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.m)),
            contentAlignment = Alignment.Center,
        ) {
            WhittleIcon(attachment.icon, size = WhittleSize.Icon.l, tint = colors.onSecondaryContainer)
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(attachment.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1)
        }
        onRemove?.let { remove ->
            val removeLabel = stringResource(Res.string.attachment_remove, attachment.name)
            Box(
                Modifier
                    .size(WhittleSize.Tile.s)
                    .clip(CircleShape)
                    .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); remove() }
                    .semantics { contentDescription = removeLabel },
                contentAlignment = Alignment.Center,
            ) {
                WhittleIcon("close", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** A sent message's photos and files, above its words, on the user's side of the thread. Tapping one opens it. */
@Composable
fun MessageAttachments(
    attachments: List<AttachmentEntry>,
    onOpen: (AttachmentEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val photos = attachments.filter { it.isImage }
    val others = attachments.filterNot { it.isImage }
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier.widthIn(max = maxWidth * MESSAGE_WIDTH_FRACTION),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        ) {
            if (photos.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s)) {
                    photos.forEach { photo -> Thumbnail(photo, Modifier.size(MessagePhoto)) { onOpen(photo) } }
                }
            }
            others.forEach { file ->
                val folder = file.path.substringBeforeLast('/', "")
                val detail = listOf(folder, sizeText(file.sizeBytes)).filter { it.isNotEmpty() }.joinToString(" · ")
                FileChip(file, detail, onOpen = { onOpen(file) })
            }
        }
    }
}

/**
 * Where an attachment comes from: the photo picker, the camera or any file. None needs a permission; what's
 * picked is saved in the workspace's uploads folder ([uploadsPath]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    uploadsPath: String,
    maxAttachments: Int,
    maxAttachmentBytes: Long,
    /** Where attachments can come from on this device. */
    sources: Map<AttachSource, () -> Unit>,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(
            Modifier.padding(horizontal = WhittleSpace.l).navigationBarsPadding().padding(bottom = WhittleSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.l),
        ) {
            Column(Modifier.padding(horizontal = WhittleSpace.s), verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
                Text(stringResource(Res.string.attach_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.attach_body, uploadsPath), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            val tiles = AttachSource.entries.mapNotNull { source ->
                val pick = sources[source] ?: return@mapNotNull null
                when (source) {
                    AttachSource.Photos -> Triple("photo_library", Res.string.attach_photos, pick)
                    AttachSource.Camera -> Triple("photo_camera", Res.string.attach_camera, pick)
                    AttachSource.Files -> Triple("attach_file", Res.string.attach_files, pick)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
                tiles.forEachIndexed { index, (icon, label, onClick) ->
                    SourceTile(icon, stringResource(label), tileShape(index, tiles.size), Modifier.weight(1f)) { onDismiss(); onClick() }
                }
            }
            Row(
                Modifier.padding(horizontal = WhittleSpace.s),
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WhittleIcon("info", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant)
                Text(
                    pluralStringResource(Res.plurals.attach_limits, maxAttachments, maxAttachments, sizeText(maxAttachmentBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Side by side, the outer corners round and the inner ones tight, as one connected group. */
private fun tileShape(index: Int, count: Int): RoundedCornerShape {
    val start = if (index == 0) WhittleRadius.xl else WhittleRadius.s
    val end = if (index == count - 1) WhittleRadius.xl else WhittleRadius.s
    return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
}

@Composable
private fun SourceTile(icon: String, label: String, shape: RoundedCornerShape, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Column(
        modifier
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .clickable(role = Role.Button) { haptics.perform(WhittleHaptic.Tap); onClick() }
            .padding(vertical = WhittleSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(
            Modifier.size(WhittleSize.touchTarget).background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.l)),
            contentAlignment = Alignment.Center,
        ) {
            WhittleIcon(icon, size = WhittleSize.Icon.xl, tint = colors.onSecondaryContainer)
        }
        Text(label, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * Centred in the chat while the next message has a photo the model can't see: the photo still goes to the
 * workspace, but the model won't look at it. Change model opens the picker.
 */
@Composable
fun ImagesUnseenNote(modelName: String, onChangeModel: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.padding(horizontal = WhittleSpace.xxxl + WhittleSpace.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(Modifier.size(WhittleSize.Tile.xl).background(colors.surfaceContainerLow, CircleShape), contentAlignment = Alignment.Center) {
            WhittleIcon("visibility_off", size = WhittleSize.Icon.xl, tint = colors.onSurfaceVariant)
        }
        Text(stringResource(Res.string.attach_unseen_title, modelName), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(Res.string.attach_unseen_body),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        onChangeModel?.let { change ->
            FilledTonalButton(onClick = change, modifier = Modifier.padding(top = WhittleSpace.xs)) {
                Text(stringResource(Res.string.attach_change_model))
            }
        }
    }
}
