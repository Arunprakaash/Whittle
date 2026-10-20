package app.whittle.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.whittle.R
import app.whittle.design.components.WhittleIcon
import app.whittle.design.tokens.WhittleSpace
import app.whittle.storage.chats.ChatId
import java.time.ZoneId

/** Every chat, in the same sections and rows as the sidebar, with search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllChatsScreen(state: ChatState, viewModel: ChatViewModel, onBack: () -> Unit, onOpen: (ChatId) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(state.chats, query) {
        val needle = query.searchable()
        val matching = if (needle.isEmpty()) state.chats else state.chats.filter { (it.title ?: "").searchable().contains(needle) }
        ChatSections.group(matching, System.currentTimeMillis(), ZoneId.systemDefault())
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { WhittleIcon("arrow_back", contentDescription = stringResource(R.string.connect_back)) } },
                title = { Text(stringResource(R.string.chats_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = WhittleSpace.l)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.chats_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (groups.isEmpty()) {
                Text(
                    stringResource(if (state.chats.isEmpty()) R.string.sidebar_empty else R.string.chats_no_match),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = WhittleSpace.l),
                )
            }
            ChatGroupList(
                groups = groups,
                current = state.currentChat,
                onOpen = onOpen,
                onPin = viewModel::setPinned,
                onDelete = viewModel::deleteChat,
                contentPadding = PaddingValues(bottom = WhittleSpace.xxl),
            )
        }
    }
}

/** Ignores case, spaces and punctuation, like the model search. */
private fun String.searchable(): String = lowercase().filter { it.isLetterOrDigit() }
