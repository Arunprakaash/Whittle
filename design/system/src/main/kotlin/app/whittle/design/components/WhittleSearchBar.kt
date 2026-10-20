package app.whittle.design.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import app.whittle.design.tokens.WhittleSize

/**
 * A search pill: a filled search icon and a clear button once there's text. A plain text field, so tapping
 * it always focuses it and opens the keyboard (Material's SearchBar expects a full-screen view to expand into).
 */
@Composable
fun WhittleSearchBar(state: TextFieldState, placeholder: String, clearLabel: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    TextField(
        state = state,
        modifier = modifier.fillMaxWidth().heightIn(min = WhittleSize.Button.default),
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { WhittleIcon("search", fill = true, size = WhittleSize.Icon.xl) },
        trailingIcon = if (state.text.isNotEmpty()) {
            { IconButton(onClick = { state.clearText() }) { WhittleIcon("close", contentDescription = clearLabel) } }
        } else {
            null
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = colors.surfaceContainerHigh,
            unfocusedContainerColor = colors.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}
