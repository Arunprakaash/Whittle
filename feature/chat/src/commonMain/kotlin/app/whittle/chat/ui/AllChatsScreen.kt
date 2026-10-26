package app.whittle.chat.ui

import app.whittle.design.window.LocalWindowChrome
import app.whittle.design.window.WindowTopBar
import app.whittle.files.ui.nowMillis
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import app.whittle.chat.ui.resources.Res
import app.whittle.chat.ui.resources.*
import app.whittle.design.components.WhittleIcon
import app.whittle.design.components.WhittleSearchBar
import app.whittle.design.tokens.WhittleSpace
import kotlinx.datetime.TimeZone

/** Every chat, in the same sections and rows as the sidebar, with search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllChatsScreen(state: ChatScreenState, onAction: (ChatAction) -> Unit, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val search = rememberTextFieldState()
    val query = search.text.toString()
    val groups = remember(state.chats, query) {
        val needle = query.searchable()
        val matching = if (needle.isEmpty()) state.chats else state.chats.filter { (it.title ?: "").searchable().contains(needle) }
        ChatSections.group(matching, nowMillis(), TimeZone.currentSystemDefault())
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            WindowTopBar {
                TopAppBar(
                    windowInsets = LocalWindowChrome.current?.insets ?: TopAppBarDefaults.windowInsets,
                    navigationIcon = { IconButton(onClick = onBack) { WhittleIcon("arrow_back", contentDescription = stringResource(Res.string.connect_back)) } },
                    title = { Text(stringResource(Res.string.chats_title), style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = WhittleSpace.l)) {
            WhittleSearchBar(search, placeholder = stringResource(Res.string.chats_search), clearLabel = stringResource(Res.string.search_clear))
            if (groups.isEmpty()) {
                Text(
                    stringResource(if (state.chats.isEmpty()) Res.string.sidebar_empty else Res.string.chats_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = WhittleSpace.l),
                )
            }
            ChatGroupList(
                groups = groups,
                current = state.currentChat,
                working = state.workingElsewhere,
                onOpen = onOpen,
                onPin = { id, pinned -> onAction(ChatAction.SetPinned(id, pinned)) },
                onDelete = { onAction(ChatAction.DeleteChat(it)) },
                contentPadding = PaddingValues(bottom = WhittleSpace.xxl),
            )
        }
    }
}

/** Ignores case, spaces and punctuation, like the model search. */
private fun String.searchable(): String = lowercase().filter { it.isLetterOrDigit() }
