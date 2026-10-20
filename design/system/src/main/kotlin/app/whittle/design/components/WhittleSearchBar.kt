package app.whittle.design.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.whittle.design.tokens.WhittleSize

/** Material 3 Expressive search: a pill with a filled search icon and a clear button once there's text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhittleSearchBar(state: TextFieldState, placeholder: String, clearLabel: String, modifier: Modifier = Modifier) {
    val searchBar = rememberSearchBarState()
    SearchBar(
        state = searchBar,
        modifier = modifier.fillMaxWidth(),
        inputField = {
            SearchBarDefaults.InputField(
                textFieldState = state,
                searchBarState = searchBar,
                onSearch = {},
                placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { WhittleIcon("search", fill = true, size = WhittleSize.Icon.xl) },
                trailingIcon = if (state.text.isNotEmpty()) {
                    { IconButton(onClick = { state.clearText() }) { WhittleIcon("close", contentDescription = clearLabel) } }
                } else {
                    null
                },
            )
        },
    )
}
