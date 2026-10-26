package app.whittle.files.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import app.whittle.design.components.ReadingWidth
import app.whittle.design.components.readingWidth
import app.whittle.design.components.TopBarAction
import app.whittle.design.components.TopBarActionGroup
import app.whittle.design.components.TopBarButton
import app.whittle.design.components.TopBarGroupAction
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleSearchBar
import app.whittle.design.components.connectedShape
import app.whittle.design.components.segmentedShape
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleRadius
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace
import app.whittle.design.tokens.WhittleTextStyles
import app.whittle.design.window.BackHandler
import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.WindowTopBar
import app.whittle.design.window.plainTextClip
import app.whittle.files.CodeColors
import app.whittle.files.DiffLine
import app.whittle.files.FileChange
import app.whittle.files.LineDiff
import app.whittle.files.ui.resources.Res
import app.whittle.files.ui.resources.*
import app.whittle.settings.FilesDefaults
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The shared workspace: its projects and folders, any file in it, and what a chat changed, line by line. Opened from the
 * chat (with that chat's changes) or from Projects. Back walks up the places visited. [onAsk] sends the user's words to
 * the chat, as Ask to undo does.
 */
@Composable
fun FilesBrowser(
    host: FilesHost,
    platform: FilesPlatform,
    opening: FilesOpening,
    onAsk: (String) -> Unit,
    onClose: () -> Unit,
) {
    val places = rememberSaveable(opening, saver = placesSaver) { mutableStateListOf(opening.place) }
    val back = { if (places.size > 1) places.removeAt(places.lastIndex) else onClose() }
    BackHandler(enabled = true) { back() }
    when (val place = places.last()) {
        is FilesPlace.Folder -> FolderScreen(host, platform, place.path, opening.chat, onBack = { back() }, onOpen = { places += it })
        is FilesPlace.Document -> DocumentScreen(
            host = host,
            platform = platform,
            place = place,
            chat = opening.chat,
            onBack = { back() },
            onMode = { changes -> places[places.lastIndex] = place.copy(changes = changes) },
            onAsk = { request -> onAsk(request); onClose() },
        )
    }
}

/** A folder: at the workspace, the chat's changes or everything; below it, everything. */
@Composable
private fun FolderScreen(host: FilesHost, platform: FilesPlatform, path: String, chat: String?, onBack: () -> Unit, onOpen: (FilesPlace) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val defaults = host.defaults
    val version by host.version.collectAsState()
    var reloads by remember { mutableIntStateOf(0) }
    val entries by produceState<List<FileEntry>?>(null, path, version, reloads) { value = host.list(path) }
    val allChanges by produceState(emptyList<FileChange>(), chat, version) { value = chat?.let { host.changes(it) }.orEmpty() }
    val changes = FileChange.within(path, allChanges)
    val atWorkspace = path.isEmpty()
    // A chat with a project opens the browser in its folder, so Changed is offered there too, and in any folder
    // the chat changed something in.
    val offerChanged = chat != null && (atWorkspace || changes.isNotEmpty())
    var chosen by rememberSaveable(path) { mutableStateOf<Boolean?>(null) }
    val showChanged = offerChanged && (chosen ?: changes.isNotEmpty())
    var searching by rememberSaveable { mutableStateOf(false) }
    val search = rememberTextFieldState()
    val query = search.text.toString().trim()
    var deleting by remember { mutableStateOf<FileEntry?>(null) }
    val unavailable = stringResource(Res.string.files_unavailable)
    Column(Modifier.fillMaxSize().background(colors.surface).safeDrawingPadding()) {
        TopBar(
            title = if (atWorkspace) stringResource(Res.string.files_title) else path.substringAfterLast('/'),
            subtitle = listOf(host.mount, path).filter { it.isNotEmpty() }.joinToString("/"),
            onBack = onBack,
        ) {
            val openInFiles = platform.openInFiles
            Actions(
                listOfNotNull(
                    Triple("search", Res.string.files_search) { searching = !searching },
                    if (atWorkspace && openInFiles != null) Triple("open_in_new", Res.string.files_open_in_files) { if (!openInFiles()) platform.toast(unavailable) } else null,
                ),
            )
        }
        // On a wide window the folder reads in a centred column; the list still scrolls across the whole width.
        Column(Modifier.readingWidth().padding(horizontal = WhittleSpace.l), verticalArrangement = Arrangement.spacedBy(WhittleSpace.l)) {
            if (searching) WhittleSearchBar(search, placeholder = stringResource(Res.string.files_search), clearLabel = stringResource(Res.string.search_clear))
            if (offerChanged) {
                Choice(
                    first = stringResource(Res.string.files_filter_changed, changes.size),
                    second = stringResource(Res.string.files_filter_all),
                    firstChosen = showChanged,
                    onChoose = { chosen = it },
                )
            }
        }
        ReadingWidth(WhittleSpace.l, Modifier.weight(1f)) { inset ->
            LazyColumn(
                contentPadding = PaddingValues(horizontal = inset, vertical = WhittleSpace.l),
                verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
            ) {
                if (showChanged) {
                    val shown = changes.filter { query.isEmpty() || it.path.contains(query, ignoreCase = true) }
                    itemsIndexed(shown, key = { _, change -> change.path }) { index, change ->
                        ChangedRow(change, path, defaults, segmentedShape(index, shown.size)) { onOpen(FilesPlace.Document(change.path, changes = true)) }
                    }
                    if (shown.isEmpty()) item { Empty(Res.string.files_no_changes) }
                } else {
                    val shown = entries.orEmpty().filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                    itemsIndexed(shown, key = { _, entry -> entry.path }) { index, entry ->
                        EntryRow(
                            entry = entry,
                            defaults = defaults,
                            shape = segmentedShape(index, shown.size),
                            onClick = { onOpen(if (entry.directory) FilesPlace.Folder(entry.path) else FilesPlace.Document(entry.path)) },
                            onLongClick = { deleting = entry },
                        )
                    }
                    if (entries != null && shown.isEmpty()) item { Empty(Res.string.files_empty) }
                }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(Res.string.files_delete_title, entry.name)) },
            text = { Text(stringResource(Res.string.files_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        host.delete(entry.path)
                        reloads++
                    }
                }) { Text(stringResource(Res.string.files_delete_confirm), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.files_cancel)) } },
        )
    }
}

/** A file: its text with the chat's changed lines marked, or its Changes; an image as itself. */
@Composable
private fun DocumentScreen(
    host: FilesHost,
    platform: FilesPlatform,
    place: FilesPlace.Document,
    chat: String?,
    onBack: () -> Unit,
    onMode: (Boolean) -> Unit,
    onAsk: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val defaults = host.defaults
    val version by host.version.collectAsState()
    val name = place.path.substringAfterLast('/')
    val undoRequest = stringResource(Res.string.files_undo_request, place.path)
    val content by produceState<FileContent?>(null, place.path, version) { value = host.open(place.path) }
    val change by produceState<FileChange?>(null, chat, place.path, version) {
        value = chat?.let { host.changes(it).firstOrNull { change -> change.path == place.path } }
    }
    val text = content as? FileContent.Text
    val diff = remember(text, change) {
        val known = change?.takeIf { it.comparable } ?: return@remember emptyList()
        text ?: return@remember emptyList()
        LineDiff.diff(known.baseline?.let(::textLines).orEmpty(), text.lines, defaults.maxDiffCells)
    }
    val added = diff.count { it is DiffLine.Added }
    val removed = diff.count { it is DiffLine.Removed }
    val showChanges = place.changes && (added + removed) > 0
    // A file the chat made is all new; marking every line would mark nothing.
    val marked = remember(diff, change) {
        if (change?.created == true) emptySet() else diff.filterIsInstance<DiffLine.Added>().mapTo(HashSet()) { it.number }
    }
    val share = platform.share?.let { share -> { share(place.path, name) } }
    Column(Modifier.fillMaxSize().background(colors.surface).safeDrawingPadding()) {
        val parent = place.path.substringBeforeLast('/', "").ifEmpty { host.mount }
        TopBar(
            title = name,
            subtitle = text?.let { "$parent · " + pluralStringResource(Res.plurals.files_lines, it.lines.size, it.lines.size) } ?: parent,
            onBack = onBack,
        ) {
            Actions(
                listOfNotNull(
                    text?.let { Triple("content_copy", Res.string.files_copy) { scope.launch { clipboard.setClipEntry(plainTextClip(name, it.lines.joinToString("\n"))) } } },
                    share?.let { Triple("share", Res.string.files_share, it) },
                ),
            )
        }
        // On a wide window the file reads in a centred column, as the chat does.
        Column(Modifier.weight(1f).readingWidth()) {
            if (added + removed > 0) {
                Box(Modifier.padding(start = WhittleSpace.l, end = WhittleSpace.l, bottom = WhittleSpace.m)) {
                    Choice(
                        first = stringResource(Res.string.files_view_file),
                        second = if (removed > 0) stringResource(Res.string.files_view_changes, added, removed) else stringResource(Res.string.files_view_added, added),
                        firstChosen = !showChanges,
                        onChoose = { file -> onMode(!file) },
                    )
                }
            }
            when (val shown = content) {
                null -> Unit
                is FileContent.Text -> if (showChanges) {
                    ChangesView(diff, change, defaults, name, chat != null) { onAsk(undoRequest) }
                } else {
                    TextView(shown, marked, defaults, name)
                }
                FileContent.Picture -> {
                    val picture by produceState<ImageBitmap?>(null, place.path, version) { value = host.image(place.path, defaults.maxImageSidePx) }
                    picture?.let { Image(bitmap = it, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(WhittleSpace.l)) }
                }
                is FileContent.Pdf -> if (shown.pageRatios.isEmpty()) {
                    NotShown(name, share, Modifier.fillMaxSize())
                } else {
                    PdfPages(host, place.path, shown.pageRatios, defaults.maxImageSidePx, Modifier.fillMaxSize())
                }
                FileContent.NotText -> NotShown(name, share, Modifier.fillMaxSize())
            }
        }
    }
}

/** A PDF as its pages, one under another, each drawn as it scrolls into view at the screen's width (at most [maxSide]). */
@Composable
private fun PdfPages(host: FilesHost, path: String, ratios: List<Float>, maxSide: Int, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceAtMost(maxSide)
        LazyColumn(
            contentPadding = PaddingValues(start = WhittleSpace.m, end = WhittleSpace.m, bottom = WhittleSpace.m),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        ) {
            itemsIndexed(ratios) { index, ratio ->
                val label = stringResource(Res.string.files_pdf_page, index + 1, ratios.size)
                val page by produceState<ImageBitmap?>(null, path, index, widthPx) { value = host.pdfPage(path, index, widthPx) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(ratio)
                        .clip(RoundedCornerShape(WhittleRadius.s))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    page?.let { Image(it, contentDescription = label, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                        ?: Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TextView(text: FileContent.Text, marked: Set<Int>, defaults: FilesDefaults, name: String) {
    val colors = MaterialTheme.colorScheme
    val code = rememberCodeColors(defaults, name)
    LazyColumn(
        Modifier
            .padding(start = WhittleSpace.m, end = WhittleSpace.m, bottom = WhittleSpace.m)
            .clip(RoundedCornerShape(WhittleRadius.lIncreased))
            .background(colors.surfaceContainerLow),
        contentPadding = PaddingValues(vertical = WhittleSpace.m),
    ) {
        itemsIndexed(text.lines) { index, line ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(WhittleSpace.xs).fillMaxHeight().background(if (index + 1 in marked) colors.primary else colors.surfaceContainerLow, CircleShape))
                LineNumber((index + 1).toString())
                Text(code.line(line), style = WhittleTextStyles.mono, color = colors.onSurface, modifier = Modifier.padding(end = WhittleSpace.m))
            }
        }
        if (text.truncated) item { Empty(Res.string.files_truncated) }
    }
}

@Composable
private fun ChangesView(diff: List<DiffLine>, change: FileChange?, defaults: FilesDefaults, name: String, canAsk: Boolean, onUndo: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val code = rememberCodeColors(defaults, name)
    val lines = remember(diff) { LineDiff.fold(diff, defaults.diffContextLines) }
    Column(Modifier.fillMaxSize()) {
        change?.let {
            Row(
                Modifier.padding(start = WhittleSpace.l, end = WhittleSpace.l, bottom = WhittleSpace.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
            ) {
                WhittleIcon("history", size = WhittleSize.Icon.m, tint = colors.onSurfaceVariant)
                Text(pluralStringResource(Res.plurals.files_since, it.edits, it.edits), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        LazyColumn(
            Modifier
                .weight(1f)
                .padding(horizontal = WhittleSpace.m)
                .clip(RoundedCornerShape(WhittleRadius.lIncreased))
                .background(colors.surfaceContainerLow),
            contentPadding = PaddingValues(vertical = WhittleSpace.m),
        ) {
            itemsIndexed(lines) { _, line ->
                val (mark, number, text, background) = when (line) {
                    is DiffLine.Same -> Quad("", line.number.toString(), line.text, colors.surfaceContainerLow)
                    is DiffLine.Added -> Quad(ADDED, line.number.toString(), line.text, colors.primaryContainer.copy(alpha = CHANGE_ALPHA))
                    is DiffLine.Removed -> Quad(REMOVED, "", line.text, colors.errorContainer.copy(alpha = CHANGE_ALPHA))
                    is DiffLine.Folded -> Quad("", "", "", colors.surfaceContainerHigh)
                }
                Row(Modifier.fillMaxWidth().background(background), verticalAlignment = Alignment.Top) {
                    if (line is DiffLine.Folded) {
                        Text(
                            pluralStringResource(Res.plurals.files_folded, line.lines, line.lines),
                            style = WhittleTextStyles.mono,
                            color = colors.outline,
                            modifier = Modifier.padding(horizontal = WhittleSpace.l),
                        )
                    } else {
                        Text(
                            mark,
                            style = WhittleTextStyles.mono,
                            color = if (line is DiffLine.Removed) colors.error else colors.primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(WhittleSpace.xl),
                        )
                        LineNumber(number)
                        Text(code.line(text), style = WhittleTextStyles.mono, color = colors.onSurface, modifier = Modifier.padding(end = WhittleSpace.m))
                    }
                }
            }
        }
        if (canAsk) {
            FilledTonalButton(
                onClick = onUndo,
                modifier = Modifier.fillMaxWidth().padding(WhittleSpace.m).height(WhittleSize.Button.default),
            ) {
                WhittleIcon("undo", size = WhittleSize.Icon.l)
                Spacer(Modifier.width(WhittleSpace.s))
                Text(stringResource(Res.string.files_undo), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun LineNumber(number: String) {
    Text(
        number,
        style = WhittleTextStyles.mono,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.End,
        modifier = Modifier.widthIn(min = WhittleSize.Tile.s).padding(end = WhittleSpace.m),
    )
}

@Composable
private fun rememberCodeColors(defaults: FilesDefaults, name: String): CodeColors {
    val colors = MaterialTheme.colorScheme
    return remember(defaults, name, colors) {
        CodeColors(defaults.isCode(name), defaults.keywords.toSet(), defaults.commentFor(name), keyword = colors.primary, string = colors.secondary, note = colors.outline)
    }
}

/** Back, the place's name over its path, and its actions as one connected group. */
@Composable
private fun TopBar(title: String, subtitle: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    // Where the app draws the window's top (a Mac's), the bar sits under the window's buttons and moves the window.
    val chrome = LocalWindowChrome.current?.insets ?: WindowInsets(0)
    WindowTopBar {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(chrome).padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
        ) {
            TopBarButton("arrow_back", stringResource(Res.string.files_back), onBack)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = WhittleTextStyles.mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions()
        }
    }
}

@Composable
private fun Actions(actions: List<Triple<String, StringResource, () -> Unit>>) {
    when (actions.size) {
        0 -> Unit
        1 -> actions.single().let { (icon, label, onClick) -> TopBarButton(icon, stringResource(label), onClick) }
        else -> TopBarActionGroup {
            actions.forEachIndexed { index, (icon, label, onClick) ->
                TopBarAction(icon, stringResource(label), onClick, shape = connectedShape(index, actions.size), modifier = TopBarGroupAction)
            }
        }
    }
}

/** Two connected choices, the chosen one filled with a check. */
@Composable
private fun Choice(first: String, second: String, firstChosen: Boolean, onChoose: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val haptics = WhittleTheme.haptics
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(WhittleSpace.hairline)) {
        listOf(first to true, second to false).forEachIndexed { index, (label, value) ->
            val chosen = firstChosen == value
            val content = if (chosen) colors.onSecondaryContainer else colors.onSurface
            Row(
                Modifier
                    .weight(1f)
                    .height(WhittleSize.Button.small)
                    .clip(connectedShape(index, 2))
                    .background(if (chosen) colors.secondaryContainer else colors.surfaceContainerHigh)
                    .selectable(selected = chosen, role = Role.RadioButton) { haptics.perform(WhittleHaptic.Select); onChoose(value) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (chosen) {
                    WhittleIcon("check", size = WhittleSize.Icon.l, tint = content)
                    Spacer(Modifier.width(WhittleSpace.s))
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChangedRow(change: FileChange, folder: String, defaults: FilesDefaults, shape: RoundedCornerShape, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Inside a folder, where it is reads from that folder down.
    val shownPath = if (folder.isEmpty()) change.path else change.path.removePrefix("$folder/")
    val name = shownPath.substringAfterLast('/')
    val parent = shownPath.substringBeforeLast('/', "")
    val now = nowMillis()
    val ago = if (now - change.lastAtMillis < MILLIS_PER_MINUTE) stringResource(Res.string.files_just_now) else timeAgo(change.lastAtMillis, now)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerLow).clickable(onClick = onClick)
            .padding(start = WhittleSpace.m, top = WhittleSpace.m, end = WhittleSpace.l, bottom = WhittleSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(Modifier.size(WhittleSize.Tile.m).background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.m)), contentAlignment = Alignment.Center) {
            WhittleIcon(defaults.iconFor(name, directory = false), size = WhittleSize.Icon.l, tint = colors.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(parent, ago).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val (badge, container, content) = if (change.created) {
            Triple(Res.string.files_badge_new, colors.secondaryContainer, colors.onSecondaryContainer)
        } else {
            Triple(Res.string.files_badge_edited, colors.tertiaryContainer, colors.onTertiaryContainer)
        }
        Text(
            stringResource(badge),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.background(container, CircleShape).padding(horizontal = WhittleSpace.s, vertical = WhittleSpace.xs),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(entry: FileEntry, defaults: FilesDefaults, shape: RoundedCornerShape, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors.surfaceContainerLow).combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = WhittleSpace.m, top = WhittleSpace.s, end = WhittleSpace.l, bottom = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.m),
    ) {
        Box(
            Modifier.size(WhittleSize.Tile.s).background(if (entry.directory) colors.surfaceContainerHigh else colors.surfaceContainerLow, RoundedCornerShape(WhittleRadius.s)),
            contentAlignment = Alignment.Center,
        ) {
            WhittleIcon(defaults.iconFor(entry.name, entry.directory), size = WhittleSize.Icon.m, tint = if (entry.directory) colors.onSurface else colors.onSurfaceVariant)
        }
        Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(
            if (entry.directory) pluralStringResource(Res.plurals.files_items, entry.items, entry.items) else sizeText(entry.size),
            style = WhittleTextStyles.mono,
            color = colors.onSurfaceVariant,
        )
        if (entry.directory) WhittleIcon("chevron_right", size = WhittleSize.Icon.l, tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun Empty(text: StringResource) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(WhittleSpace.l),
    )
}

/** A file Whittle can't show, such as a zip or a spreadsheet: what it is, and Share to open it in another app where there's one. */
@Composable
private fun NotShown(name: String, onShare: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.padding(WhittleSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhittleSpace.m, Alignment.CenterVertically),
    ) {
        Box(
            Modifier.size(WhittleSize.Tile.xl).background(colors.secondaryContainer, RoundedCornerShape(WhittleRadius.l)),
            contentAlignment = Alignment.Center,
        ) {
            WhittleIcon("description", size = WhittleSize.Icon.xl, tint = colors.onSecondaryContainer)
        }
        Text(name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            stringResource(Res.string.files_not_text),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        onShare?.let { share ->
            FilledTonalButton(onClick = share) {
                WhittleIcon("share", size = WhittleSize.Icon.m)
                Spacer(Modifier.width(WhittleSpace.s))
                Text(stringResource(Res.string.files_share))
            }
        }
    }
}

private data class Quad(val mark: String, val number: String, val text: String, val background: Color)

/** A text's lines; the newline that ends the last one doesn't start another. */
fun textLines(text: String): List<String> = text.removeSuffix("\n").lines()

/** Keeps an open browser across configuration changes and process death. */
val filesOpeningSaver = Saver<FilesOpening?, String>(
    save = { opening -> opening?.let { Json.encodeToString(FilesOpening.serializer(), it) } },
    restore = { saved -> Json.decodeFromString(FilesOpening.serializer(), saved) },
)

private val placesSaver = listSaver<MutableList<FilesPlace>, String>(
    save = { places -> places.map { Json.encodeToString(FilesPlace.serializer(), it) } },
    restore = { saved -> mutableStateListOf(*saved.map { Json.decodeFromString(FilesPlace.serializer(), it) }.toTypedArray()) },
)

private const val ADDED = "+"
private const val REMOVED = "−"
private const val MILLIS_PER_MINUTE = 60_000L

/** Changed lines take a light wash of the change's colour, so the text stays easy to read. */
private const val CHANGE_ALPHA = 0.5f
