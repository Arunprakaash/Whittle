package app.whittle.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.whittle.AppGraph
import app.whittle.R
import app.whittle.core.common.getOrNull
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
import app.whittle.settings.FilesDefaults
import app.whittle.tools.api.WorkspaceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/** A place in the workspace, by its path in it; "" is the workspace itself. */
@Serializable
sealed interface FilesPlace {
    @Serializable
    data class Folder(val path: String) : FilesPlace

    @Serializable
    data class Document(val path: String, val changes: Boolean = false) : FilesPlace
}

/** Opening the browser: where, and the chat it was opened from (its Changed filter and Ask to undo), if any. */
@Serializable
data class FilesOpening(val place: FilesPlace, val chat: String? = null)

/**
 * The shared workspace, inside Whittle: its projects and folders, any file in it, and what a chat changed, line by
 * line. Opened from the chat (with that chat's changes) or from Projects. Back walks up the places visited.
 */
@Composable
fun FilesBrowser(graph: AppGraph, opening: FilesOpening, onAsk: (String) -> Unit, onClose: () -> Unit) {
    val places = rememberSaveable(opening, saver = placesSaver) { mutableStateListOf(opening.place) }
    val back = { if (places.size > 1) places.removeAt(places.lastIndex) else onClose() }
    BackHandler(onBack = { back() })
    val chat = opening.chat?.let(::WorkspaceId)
    when (val place = places.last()) {
        is FilesPlace.Folder -> FolderScreen(graph, place.path, chat, onBack = { back() }, onOpen = { places += it })
        is FilesPlace.Document -> DocumentScreen(
            graph = graph,
            place = place,
            chat = chat,
            onBack = { back() },
            onMode = { changes -> places[places.lastIndex] = place.copy(changes = changes) },
            onAsk = { request -> onAsk(request); onClose() },
        )
    }
}

/** A folder: at the workspace, the chat's changes or everything; below it, everything. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderScreen(graph: AppGraph, path: String, chat: WorkspaceId?, onBack: () -> Unit, onOpen: (FilesPlace) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val defaults = graph.filesDefaults
    val version by graph.fileChanges.version.collectAsStateWithLifecycle()
    var reloads by remember { mutableIntStateOf(0) }
    val entries by produceState<List<Entry>?>(null, path, version, reloads) { value = withContext(Dispatchers.IO) { list(graph, path) } }
    val changes by produceState(emptyList<FileChange>(), chat, version) { value = chat?.let { graph.fileChanges.changes(it) }.orEmpty() }
    val atWorkspace = path.isEmpty()
    var chosen by rememberSaveable(path) { mutableStateOf<Boolean?>(null) }
    val showChanged = atWorkspace && chat != null && (chosen ?: changes.isNotEmpty())
    var searching by rememberSaveable { mutableStateOf(false) }
    val search = rememberTextFieldState()
    val query = search.text.toString().trim()
    var deleting by remember { mutableStateOf<Entry?>(null) }
    Column(Modifier.fillMaxSize().background(colors.surface).safeDrawingPadding()) {
        TopBar(
            title = if (atWorkspace) stringResource(R.string.files_title) else path.substringAfterLast('/'),
            subtitle = listOf(graph.linux.workspaceMount, path).filter { it.isNotEmpty() }.joinToString("/"),
            onBack = onBack,
        ) {
            val actions = listOfNotNull(
                Triple("search", R.string.files_search) { searching = !searching },
                Triple("open_in_new", R.string.files_open_in_files) {
                    if (!WorkspaceDocumentsProvider.open(context)) Toast.makeText(context, R.string.files_unavailable, Toast.LENGTH_SHORT).show()
                }.takeIf { atWorkspace },
            )
            Actions(actions)
        }
        Column(Modifier.padding(horizontal = WhittleSpace.l), verticalArrangement = Arrangement.spacedBy(WhittleSpace.l)) {
            if (searching) WhittleSearchBar(search, placeholder = stringResource(R.string.files_search), clearLabel = stringResource(R.string.search_clear))
            if (atWorkspace && chat != null) {
                Choice(
                    first = stringResource(R.string.files_filter_changed, changes.size),
                    second = stringResource(R.string.files_filter_all),
                    firstChosen = showChanged,
                    onChoose = { chosen = it },
                )
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(WhittleSpace.l),
            verticalArrangement = Arrangement.spacedBy(WhittleSpace.hairline),
        ) {
            if (showChanged) {
                val shown = changes.filter { query.isEmpty() || it.path.contains(query, ignoreCase = true) }
                itemsIndexed(shown, key = { _, change -> change.path }) { index, change ->
                    ChangedRow(change, defaults, segmentedShape(index, shown.size)) { onOpen(FilesPlace.Document(change.path, changes = true)) }
                }
                if (shown.isEmpty()) item { Empty(R.string.files_no_changes) }
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
                if (entries != null && shown.isEmpty()) item { Empty(R.string.files_empty) }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.files_delete_title, entry.name)) },
            text = { Text(stringResource(R.string.files_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        withContext(Dispatchers.IO) { graph.workspaces.resolve(entry.path).getOrNull()?.deleteRecursively() }
                        reloads++
                    }
                }) { Text(stringResource(R.string.files_delete_confirm), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.files_cancel)) } },
        )
    }
}

/** A file: its text with the chat's changed lines marked, or its Changes; an image as itself. */
@Composable
private fun DocumentScreen(
    graph: AppGraph,
    place: FilesPlace.Document,
    chat: WorkspaceId?,
    onBack: () -> Unit,
    onMode: (Boolean) -> Unit,
    onAsk: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val defaults = graph.filesDefaults
    val version by graph.fileChanges.version.collectAsStateWithLifecycle()
    val name = place.path.substringAfterLast('/')
    val undoRequest = stringResource(R.string.files_undo_request, place.path)
    val content by produceState<Content?>(null, place.path, version) { value = withContext(Dispatchers.IO) { load(graph, place.path, defaults) } }
    val change by produceState<FileChange?>(null, chat, place.path, version) {
        value = chat?.let { graph.fileChanges.changes(it).firstOrNull { change -> change.path == place.path } }
    }
    val text = content as? Content.Text
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
    Column(Modifier.fillMaxSize().background(colors.surface).safeDrawingPadding()) {
        val parent = place.path.substringBeforeLast('/', "").ifEmpty { graph.linux.workspaceMount }
        TopBar(
            title = name,
            subtitle = text?.let { "$parent · " + pluralStringResource(R.plurals.files_lines, it.lines.size, it.lines.size) } ?: parent,
            onBack = onBack,
        ) {
            Actions(
                listOfNotNull(
                    text?.let { Triple("content_copy", R.string.files_copy) { copy(context, name, it.lines.joinToString("\n")) } },
                    Triple("share", R.string.files_share) { share(context, place.path, name) },
                ),
            )
        }
        if (added + removed > 0) {
            Box(Modifier.padding(start = WhittleSpace.l, end = WhittleSpace.l, bottom = WhittleSpace.m)) {
                Choice(
                    first = stringResource(R.string.files_view_file),
                    second = if (removed > 0) stringResource(R.string.files_view_changes, added, removed) else stringResource(R.string.files_view_added, added),
                    firstChosen = !showChanges,
                    onChoose = { file -> onMode(!file) },
                )
            }
        }
        when (val shown = content) {
            null -> Unit
            is Content.Text -> if (showChanges) {
                ChangesView(diff, change, defaults, name, chat != null) { onAsk(undoRequest) }
            } else {
                TextView(shown, marked, defaults, name)
            }
            is Content.Picture -> Image(
                bitmap = shown.bitmap,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(WhittleSpace.l),
            )
            Content.NotText -> Empty(R.string.files_not_text)
        }
    }
}

@Composable
private fun TextView(text: Content.Text, marked: Set<Int>, defaults: FilesDefaults, name: String) {
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
        if (text.truncated) item { Empty(R.string.files_truncated) }
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
                Text(pluralStringResource(R.plurals.files_since, it.edits, it.edits), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
                            pluralStringResource(R.plurals.files_folded, line.lines, line.lines),
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
                Text(stringResource(R.string.files_undo), style = MaterialTheme.typography.labelLarge)
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = WhittleSpace.m, vertical = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        TopBarButton("arrow_back", stringResource(R.string.files_back), onBack)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = WhittleTextStyles.mono, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
private fun Actions(actions: List<Triple<String, Int, () -> Unit>>) {
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
private fun ChangedRow(change: FileChange, defaults: FilesDefaults, shape: RoundedCornerShape, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val name = change.path.substringAfterLast('/')
    val parent = change.path.substringBeforeLast('/', "")
    val now = System.currentTimeMillis()
    val ago = if (now - change.lastAtMillis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.files_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(change.lastAtMillis, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
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
            Triple(R.string.files_badge_new, colors.secondaryContainer, colors.onSecondaryContainer)
        } else {
            Triple(R.string.files_badge_edited, colors.tertiaryContainer, colors.onTertiaryContainer)
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
private fun EntryRow(entry: Entry, defaults: FilesDefaults, shape: RoundedCornerShape, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
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
            if (entry.directory) pluralStringResource(R.plurals.files_items, entry.items, entry.items) else Formatter.formatShortFileSize(context, entry.size),
            style = WhittleTextStyles.mono,
            color = colors.onSurfaceVariant,
        )
        if (entry.directory) WhittleIcon("chevron_right", size = WhittleSize.Icon.l, tint = colors.onSurfaceVariant)
    }
}

@Composable
private fun Empty(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(WhittleSpace.l),
    )
}

/** One thing in a folder. */
private data class Entry(val name: String, val path: String, val directory: Boolean, val size: Long, val items: Int)

/** What a file shows as. */
private sealed interface Content {
    data class Text(val lines: List<String>, val truncated: Boolean) : Content

    data class Picture(val bitmap: ImageBitmap) : Content

    data object NotText : Content
}

private data class Quad(val mark: String, val number: String, val text: String, val background: androidx.compose.ui.graphics.Color)

/** A folder's contents, folders first, both by name; Whittle's own files are left out. */
private fun list(graph: AppGraph, path: String): List<Entry> {
    val folder = graph.workspaces.resolve(path.ifEmpty { CURRENT }).getOrNull() ?: return emptyList()
    val hidden = graph.filesDefaults.hiddenNames
    return folder.listFiles().orEmpty()
        .filter { it.name !in hidden }
        .map { file ->
            Entry(
                name = file.name,
                path = listOf(path, file.name).filter { it.isNotEmpty() }.joinToString("/"),
                directory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                items = if (file.isDirectory) file.list()?.size ?: 0 else 0,
            )
        }
        .sortedWith(compareBy<Entry>({ !it.directory }, { it.name.lowercase() }))
}

private fun load(graph: AppGraph, path: String, defaults: FilesDefaults): Content {
    val file = graph.workspaces.resolve(path).getOrNull()?.takeIf { it.isFile } ?: return Content.NotText
    if (defaults.isImage(file.name)) return picture(file, defaults.maxImageSidePx) ?: Content.NotText
    return try {
        val buffer = CharArray(defaults.maxPreviewChars + 1)
        val read = file.bufferedReader().use { reader ->
            var total = 0
            while (total < buffer.size) {
                val count = reader.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        val text = String(buffer, 0, minOf(read, defaults.maxPreviewChars))
        if (NUL in text) return Content.NotText
        Content.Text(textLines(text), truncated = read > defaults.maxPreviewChars)
    } catch (exception: IOException) {
        Content.NotText
    }
}

/** A text's lines; the newline that ends the last one doesn't start another. */
private fun textLines(text: String): List<String> = text.removeSuffix("\n").lines()

private fun picture(file: File, maxSide: Int): Content.Picture? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return Content.Picture(bitmap.asImageBitmap())
}

private fun copy(context: Context, name: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(name, text))
}

private fun share(context: Context, path: String, name: String) {
    val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: FALLBACK_TYPE
    val send = Intent(Intent.ACTION_SEND)
        .setType(type)
        .putExtra(Intent.EXTRA_STREAM, WorkspaceDocumentsProvider.documentUri(context, path))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (exception: ActivityNotFoundException) {
        Toast.makeText(context, R.string.files_unavailable, Toast.LENGTH_SHORT).show()
    }
}

/** Keeps an open browser across configuration changes and process death. */
val filesOpeningSaver = Saver<FilesOpening?, String>(
    save = { opening -> opening?.let { Json.encodeToString(FilesOpening.serializer(), it) } },
    restore = { saved -> Json.decodeFromString(FilesOpening.serializer(), saved) },
)

private val placesSaver = listSaver<MutableList<FilesPlace>, String>(
    save = { places -> places.map { Json.encodeToString(FilesPlace.serializer(), it) } },
    restore = { saved -> mutableStateListOf(*saved.map { Json.decodeFromString(FilesPlace.serializer(), it) }.toTypedArray()) },
)

private const val CURRENT = "."
private const val NUL = '\u0000'
private const val ADDED = "+"
private const val REMOVED = "−"
private const val FALLBACK_TYPE = "application/octet-stream"
/** Changed lines take a light wash of the change's colour, so the text stays easy to read. */
private const val CHANGE_ALPHA = 0.5f
