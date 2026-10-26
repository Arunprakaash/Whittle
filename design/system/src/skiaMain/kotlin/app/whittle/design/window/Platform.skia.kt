package app.whittle.design.window

import androidx.compose.runtime.Composable

// A computer's window has no back gesture; Escape and the window's own controls do that.
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
