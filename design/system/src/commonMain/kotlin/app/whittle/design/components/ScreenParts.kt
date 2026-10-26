package app.whittle.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.whittle.design.theme.WhittleTheme
import app.whittle.design.tokens.WhittleHaptic
import app.whittle.design.tokens.WhittleSize
import app.whittle.design.tokens.WhittleSpace

/** A screen's large title with a line under it, at the top of its list. */
@Composable
fun ScreenHeading(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(WhittleSpace.xs)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A screen's main action as a full-width button pinned to the bottom, above the navigation bar, styled as the
 * first-run screens' buttons. Goes in a Scaffold's bottomBar.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BottomActionButton(icon: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = WhittleTheme.haptics
    Box(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(start = WhittleSpace.l, end = WhittleSpace.l, top = WhittleSpace.l, bottom = WhittleSpace.xl),
    ) {
        Button(
            onClick = { haptics.perform(WhittleHaptic.Tap); onClick() },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(WhittleSize.Button.large),
        ) {
            WhittleIcon(icon, size = WhittleSize.Icon.l)
            Spacer(Modifier.width(WhittleSpace.s))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A small icon and a line of supporting text, such as a tip at the end of a list. */
@Composable
fun SupportingNote(icon: String, text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = WhittleSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WhittleSpace.s),
    ) {
        WhittleIcon(icon, size = WhittleSize.Icon.m, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
